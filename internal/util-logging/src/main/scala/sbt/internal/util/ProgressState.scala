/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt.internal.util

import java.io.PrintStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }

import sbt.internal.util.ConsoleAppender.{
  ClearScreenAfterCursor,
  CursorLeft1000,
  DeleteLine,
  cursorUp
}

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters._

private[sbt] final class ProgressState(
    val progressLines: AtomicReference[Seq[String]],
    val padding: AtomicInteger,
    val blankZone: Int,
    val currentLineBytes: AtomicReference[ArrayBuffer[Byte]],
) {
  def this(blankZone: Int) =
    this(
      new AtomicReference(Nil),
      new AtomicInteger(0),
      blankZone,
      new AtomicReference(new ArrayBuffer[Byte]),
    )
  def currentLine: Option[String] =
    new String(currentLineBytes.get.toArray, "UTF-8").linesIterator.toSeq.lastOption
      .map(EscHelpers.stripColorsAndMoves)
      .filter(_.nonEmpty)
  def reset(): Unit = {
    progressLines.set(Nil)
    padding.set(0)
    currentLineBytes.set(new ArrayBuffer[Byte])
  }
  private[this] val lineBuffer = new ArrayBlockingQueue[String](300)
  private[util] def getLines: Seq[String] = lineBuffer.asScala.toVector
  private[this] def appendLine(line: String) = while (!lineBuffer.offer(line)) { lineBuffer.poll }
  private[util] def clearBytes(): Unit = {
    val pad = padding.get
    if (currentLineBytes.get.isEmpty && pad > 0) padding.decrementAndGet()
    currentLineBytes.set(new ArrayBuffer[Byte])
  }

  private[this] val lineSeparatorBytes: Array[Byte] = System.lineSeparator.getBytes("UTF-8")
  private[util] def addBytes(terminal: Terminal, bytes: Seq[Byte]): Unit = {
    val previous: ArrayBuffer[Byte] = currentLineBytes.get
    val padding = this.padding.get
    val prevLineCount = if (padding > 0) terminal.lineCount(new String(previous.toArray)) else 0
    previous ++= bytes
    if (padding > 0) {
      val newLineCount = terminal.lineCount(new String(previous.toArray))
      val diff = newLineCount - prevLineCount
      this.padding.set(math.max(padding - diff, 0))
    }
    val lines = new String(previous.toArray, "UTF-8")
    if (lines.contains(System.lineSeparator)) {
      currentLineBytes.set(new ArrayBuffer[Byte])
      if (!lines.endsWith(System.lineSeparator)) {
        val allLines = lines.split(System.lineSeparator)
        allLines.dropRight(1).foreach(appendLine)
        allLines.lastOption
          .foreach(currentLineBytes.get ++= _.getBytes("UTF-8"))
      } else if (lines.contains(System.lineSeparator)) {
        lines.split(System.lineSeparator).foreach(appendLine)
      }
    }
  }

  private[util] def getPrompt(terminal: Terminal): Array[Byte] = {
    if (terminal.prompt != Prompt.Running && terminal.prompt != Prompt.Batch) {
      val prefix = if (terminal.isAnsiSupported) s"$DeleteLine$CursorLeft1000" else ""
      prefix.getBytes ++ terminal.prompt.render().getBytes("UTF-8")
    } else Array.empty
  }
  private[util] def write(
      terminal: Terminal,
      bytes: Array[Byte],
      printStream: PrintStream,
      hasProgress: Boolean
  ): Unit = {
    addBytes(terminal, bytes)
    if (hasProgress) {
      val toWrite = new ArrayBuffer[Byte]
      terminal.prompt match {
        case a: Prompt.AskUser if a.render.nonEmpty =>
          toWrite ++= (DeleteLine + ClearScreenAfterCursor + CursorLeft1000).getBytes("UTF-8")
        case _ =>
      }
      toWrite ++= bytes
      toWrite ++= ClearScreenAfterCursor.getBytes("UTF-8")
      if (bytes.endsWith(lineSeparatorBytes)) {
        if (progressLines.get.nonEmpty) {
          val lastLine = terminal.prompt match {
            case a: Prompt.AskUser => a.render()
            case _                 => currentLine.getOrElse("")
          }
          val lines = printProgress(terminal, lastLine)
          toWrite ++= (ClearScreenAfterCursor + lines).getBytes("UTF-8")
        }
      }
      toWrite ++= getPrompt(terminal)
      printStream.write(toWrite.toArray)
      printStream.flush()
    } else printStream.write(bytes)
  }

  private[util] def printProgress(terminal: Terminal, lastLine: String): String = {
    val previousLines = progressLines.get
    if (previousLines.nonEmpty) {
      val currentLength = previousLines.foldLeft(0)(_ + terminal.lineCount(_))
      val (height, width) = terminal.getLineHeightAndWidth(lastLine)
      val offset = width > 0
      val pad = math.max(padding.get - height, 0)
      val start = (if (offset) s"\n$CursorLeft1000" else "")
      val totalSize = currentLength + blankZone + pad
      val blank = CursorLeft1000 + s"\n$DeleteLine" * (totalSize - currentLength)
      val lines = previousLines.mkString(DeleteLine, s"\n$DeleteLine", s"\n$DeleteLine")
      val resetCursorUp = cursorUp(totalSize + (if (offset) 1 else 0))
      val resetCursor = resetCursorUp + CursorLeft1000 + lastLine
      start + blank + lines + resetCursor
    } else {
      ClearScreenAfterCursor
    }
  }
}

private[sbt] object ProgressState {

  /**
   * Receives a new task report and replaces the old one. In the event that the new
   * report has fewer lines than the previous report, padding lines are added on top
   * so that the console log lines remain contiguous. When a console line is printed
   * at the info or greater level, we can decrement the padding because the console
   * line will have filled in the blank line.
   */
  private[sbt] def updateProgressState(
      pe: ProgressEvent,
      terminal: Terminal
  ): Unit = {
    val state = terminal.progressState
    val isAskUser = terminal.prompt.isInstanceOf[Prompt.AskUser]
    val isRunning = terminal.prompt == Prompt.Running
    val isBatch = terminal.prompt == Prompt.Batch
    val isWatch = terminal.prompt == Prompt.Watch
    val noPrompt = terminal.prompt == Prompt.NoPrompt
    if (terminal.isSupershellEnabled) {
      if (!pe.skipIfActive.getOrElse(false) || (!isRunning && !isBatch)) {
        terminal.withPrintStream { ps =>
          val commandFromThisTerminal = pe.channelName.fold(true)(_ == terminal.name)
          val info = if ((isRunning || isBatch || noPrompt) && commandFromThisTerminal) {
            pe.items.map { item =>
              val elapsed = item.elapsedMicros / 1000000L
              s"  | => ${item.name} ${elapsed}s"
            }
          } else {
            pe.command.toSeq.flatMap { cmd =>
              val tail = if (isWatch) Nil else "enter 'cancel' to stop evaluation" :: Nil
              s"sbt server is running '$cmd'" :: tail
            }
          }

          val currentLength = info.foldLeft(0)(_ + terminal.lineCount(_))
          val previousLines = state.progressLines.getAndSet(info)
          val prevLength = previousLines.foldLeft(0)(_ + terminal.lineCount(_))
          val prevSize = prevLength + state.padding.get

          val lastLine =
            if (isAskUser) terminal.prompt.render() else terminal.getLastLine.getOrElse("")
          state.padding.set(math.max(0, prevSize - currentLength))
          val toWrite =
            state.getPrompt(terminal) ++ state.printProgress(terminal, lastLine).getBytes("UTF-8")
          ps.write(toWrite)
          ps.flush()
        }
      } else if (state.progressLines.get.nonEmpty) {
        state.progressLines.set(Nil)
        terminal.withPrintStream { ps =>
          val lastLine = terminal.getLastLine.getOrElse("")
          ps.print(lastLine + ClearScreenAfterCursor)
          ps.flush()
        }
      }
    }
  }
}
