/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell.tui

import com.googlecode.lanterna.{TerminalSize, TextColor}
import com.googlecode.lanterna.graphics.TextGraphics
import com.googlecode.lanterna.input.{KeyStroke, KeyType}
import com.googlecode.lanterna.screen.{Screen, TerminalScreen}
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import zio.*

import java.time.format.DateTimeFormatter
import scala.language.unsafeNulls

/** Lanterna-based split-screen REPL UI.
  *
  * Layout:
  * {{{
  * ┌──────────────────────────────────────────┐ row 0
  * │  ● Jorlan Shell  [user@host]  [connected] │ status bar      (blue)
  * ├──────────────────────────────────────────┤
  * │ [HH:mm:ss]  ⚙  system message            │
  * │ [HH:mm:ss]  ❯  user typed this           │ conversation
  * │ [HH:mm:ss]  ✦  jorlan response           │ (scrollable)
  * │                                          │
  * ├──────────────────────────────────────────┤ row height-4
  * │ ❯ _                                      │ input line
  * ├──────────────────────────────────────────┤ row height-2 (separator)
  * │  [mode]  [server]  [session]             │ mode/status bar (black)
  * └──────────────────────────────────────────┘ row height-1
  * }}}
  *
  * All Lanterna operations run inside a single `ZIO.blocking` call per frame to ensure thread safety. A single
  * `Ref[ScreenState]` mediates between the rendering fiber and the command-processing fiber, eliminating
  * partial-observation windows that would exist with separate Refs (P7-023/P7-026).
  */
trait JorlanScreen {

  /** Append a message to the conversation area. Thread-safe. */
  def addMessage(
    kind:    MessageKind,
    content: String,
  ): UIO[Unit]

  /** Append text to the last message of the given kind, or start a new one if the last message is a different kind.
    * Used for streaming token accumulation so all tokens appear in a single message line.
    */
  def appendToLastMessage(
    kind:  MessageKind,
    extra: String,
  ): UIO[Unit]

  /** Update the status bar text. Thread-safe. */
  def setStatus(text: String): UIO[Unit]

  /** Replace the prompt label shown in the input line (default "❯ "). Thread-safe. */
  def setInputPrompt(label: String): UIO[Unit]

  /** Update the mode/status bar at the bottom of the screen. Thread-safe. */
  def setModeStatus(text: String): UIO[Unit]

  /** Block until the user submits a line (presses Enter). */
  def readLine: UIO[String]

  /** Signal the rendering loop to stop. */
  def shutdown: UIO[Unit]

  /** Run the Lanterna render + input loop until [[shutdown]] is called. Intended to be forked. */
  def startRendering: UIO[Unit]

}

// ─── Display state ─────────────────────────────────────────────────────────────
// P7-023/P7-026: All mutable display state in a single Ref so the render fiber
// observes a consistent snapshot and partial-observation windows are eliminated.

private case class ScreenState(
  messages: Vector[MessageEntry],
  // The currently-streaming response held outside `messages` to avoid O(n_messages) Vector.init per token.
  // Flushed into `messages` when the kind changes or when addMessage is called.
  inProgressMessage: Option[MessageEntry],
  statusText:        String,
  inputPrompt:       String,
  modeText:          String,
  scrollOffset:      Int,
)

object JorlanScreen {

  val live: ZLayer[Any, Throwable, JorlanScreen] = ZLayer.scoped {
    ZIO
      .acquireRelease(
        ZIO.attempt {
          val screen = TerminalScreen(DefaultTerminalFactory().createTerminal())
          screen.startScreen()
          screen
        },
      )(s =>
        // P7-010: Log shutdown errors before dying so they are visible in logs.
        ZIO
          .attempt {
            s.stopScreen()
            s.close()
          }.tapError(e =>
            ZIO.logError(s"Failed to close Lanterna screen: ${Option(e.getMessage).getOrElse(e.getClass.getName)}"),
          ).orDie,
      ).flatMap { screen =>
        for {
          state <- Ref.make(
            ScreenState(
              messages = Vector.empty,
              inProgressMessage = None,
              statusText = " ● Jorlan Shell  [disconnected]",
              inputPrompt = "❯ ",
              modeText = " [no session]  [disconnected]",
              scrollOffset = 0,
            ),
          )
          inputBuf   <- Ref.make("")
          inputQueue <- Queue.bounded[String](256)
          running    <- Ref.make(true)
        } yield LanternaScreen(screen, state, inputBuf, inputQueue, running)
      }
  }

  /** Maximum number of messages retained in the scroll buffer. */
  val maxMessages: Int = 2000

}

// ─── Implementation ───────────────────────────────────────────────────────────

// $COVERAGE-OFF$ Lanterna terminal I/O requires a real TTY; not instrumentable in CI
// P7-026: Constructor reduced from 9 parameters to 5 by grouping display state into ScreenState.
private class LanternaScreen(
  screen:     Screen,
  state:      Ref[ScreenState],
  inputBuf:   Ref[String],
  inputQueue: Queue[String],
  running:    Ref[Boolean],
) extends JorlanScreen {

  private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

  override def addMessage(
    kind:    MessageKind,
    content: String,
  ): UIO[Unit] = {
    // P7-007: Use ZIO Clock so TestClock works in tests and the effect is pure.
    // P7-022: Scroll offset is NOT reset — new messages auto-appear at the tail when
    // the user is at offset=0, and their scroll position is preserved when scrolled up.
    Clock.currentDateTime.flatMap { odt =>
      val time = odt.toLocalTime.format(timeFmt)
      state.update { s =>
        // Flush any in-progress streaming message before adding the new one.
        val base = s.inProgressMessage.fold(s.messages) { ip =>
          val next = s.messages :+ ip
          if (next.size > JorlanScreen.maxMessages) next.drop(next.size - JorlanScreen.maxMessages) else next
        }
        val next = base :+ MessageEntry(kind, content, time)
        val msgs = if (next.size > JorlanScreen.maxMessages) next.drop(next.size - JorlanScreen.maxMessages) else next
        s.copy(messages = msgs, inProgressMessage = None)
      }
    }
  }

  override def appendToLastMessage(
    kind:  MessageKind,
    extra: String,
  ): UIO[Unit] =
    Clock.currentDateTime.flatMap { odt =>
      val time = odt.toLocalTime.format(timeFmt)
      state.update { s =>
        s.inProgressMessage match {
          case Some(ip) if ip.kind == kind =>
            // Same kind: update inProgressMessage in-place. O(1) — no Vector rebuild.
            s.copy(inProgressMessage = Some(ip.copy(content = ip.content + extra)))
          case existing =>
            // Kind changed or no in-progress: flush existing to messages and start a new one.
            val base = existing.fold(s.messages) { ip =>
              val next = s.messages :+ ip
              if (next.size > JorlanScreen.maxMessages) next.drop(next.size - JorlanScreen.maxMessages) else next
            }
            s.copy(messages = base, inProgressMessage = Some(MessageEntry(kind, extra, time)))
        }
      }
    }

  override def setStatus(text: String): UIO[Unit] = state.update(_.copy(statusText = text))

  override def setInputPrompt(label: String): UIO[Unit] = state.update(_.copy(inputPrompt = label))

  override def setModeStatus(text: String): UIO[Unit] = state.update(_.copy(modeText = text))

  override def readLine: UIO[String] = inputQueue.take

  override def shutdown: UIO[Unit] = running.set(false)

  override def startRendering: UIO[Unit] =
    oneFrame
      .repeatWhileZIO(_ => running.get)
      .unit

  // ─── Per-frame work: read state → poll input → draw → refresh ──────────────
  // Capped at ~30 fps: after each draw we yield for one frame period via
  // ZIO.sleep so the ZIO scheduler thread is not held and the screen does not
  // flash at thousands of redraws per second.

  private val frameDuration: Duration = 33.millis // ~30 fps

  private def oneFrame: UIO[Boolean] = {
    for {
      // P7-023: Single atomic Ref.get replaces 5 separate reads, eliminating
      // the partial-observation window in the render fiber.
      s     <- state.get
      input <- inputBuf.get
      cont  <- ZIO.blocking {
        ZIO
          .attempt {
            val keyOpt: Option[KeyStroke] = Option(screen.pollInput())
            screen.doResizeIfNecessary()
            val sz: TerminalSize = screen.getTerminalSize
            val tg: TextGraphics = screen.newTextGraphics()
            drawFrame(tg, sz.getColumns, sz.getRows, s, input)
            screen.refresh()
            keyOpt
          }.fold(_ => Option.empty[KeyStroke], identity) // survive Lanterna IOExceptions
      }
      _ <- cont.fold(ZIO.unit)(handleKey)
      _ <- ZIO.sleep(frameDuration)
      r <- running.get
    } yield r
  }

  // ─── Input handling ─────────────────────────────────────────────────────────

  private def handleKey(key: KeyStroke): UIO[Unit] = {
    key.getKeyType match {
      case KeyType.Character =>
        val ch = key.getCharacter
        if (ch == null) ZIO.unit
        else
          ch.toInt match {
            case 3  => inputQueue.offer("/quit").unit // Ctrl-C — let quit handler shut down cleanly
            case 4  => inputQueue.offer("/quit").unit // Ctrl-D
            case 12 => ZIO.unit // Ctrl-L
            case _  => inputBuf.update(_ + ch.toString)
          }
      case KeyType.Backspace =>
        inputBuf.update(s => if (s.isEmpty) "" else s.dropRight(1))
      case KeyType.Delete =>
        ZIO.unit // cursor is always end-of-line; no character to delete forward
      case KeyType.Enter =>
        inputBuf.getAndSet("").flatMap(line => inputQueue.offer(line.trim).unit)
      case KeyType.PageUp   => state.update(s => s.copy(scrollOffset = s.scrollOffset + 10))
      case KeyType.PageDown => state.update(s => s.copy(scrollOffset = (s.scrollOffset - 10) max 0))
      case KeyType.Home     => state.update(_.copy(scrollOffset = Int.MaxValue))
      case KeyType.End      => state.update(_.copy(scrollOffset = 0))
      case KeyType.EOF      => inputQueue.offer("/quit").unit
      case _                => ZIO.unit
    }
  }

  // ─── Drawing ────────────────────────────────────────────────────────────────

  private def drawFrame(
    tg:     TextGraphics,
    width:  Int,
    height: Int,
    s:      ScreenState,
    input:  String,
  ): Unit = {
    // Do NOT call screen.clear() — it marks every cell dirty and forces Lanterna
    // to repaint the full terminal on every refresh, causing visible flicker.
    // Instead every region is written in full so Lanterna's delta-refresh only
    // sends actually-changed characters.

    // P7-027: Local helper removes duplicated separator-drawing logic.
    def drawSeparator(row: Int): Unit = {
      tg.setBackgroundColor(TextColor.ANSI.DEFAULT)
      tg.setForegroundColor(TextColor.ANSI.WHITE)
      tg.putString(0, row, "─" * width)
    }

    // Status bar (row 0) — padded to full width to overwrite leftovers
    tg.setBackgroundColor(TextColor.ANSI.BLUE)
    tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT)
    tg.putString(0, 0, s.statusText.take(width).padTo(width, ' '))

    // Conversation area (rows 1 .. height-5 inclusive)
    // P7-021: expandMessages is called every frame but its result is pure wrt (msgs, width).
    //         Caching keyed on (msgs.size, width) would eliminate the per-frame re-computation
    //         when no new messages have arrived — deferred to a follow-up optimisation.
    val areaHeight = (height - 5) max 0
    tg.setBackgroundColor(TextColor.ANSI.DEFAULT)
    if (areaHeight > 0) {
      val allMessages = s.messages ++ s.inProgressMessage.toVector
      val lines = expandMessages(allMessages, width)
      val totalLines = lines.size
      val scrolled = s.scrollOffset min (totalLines - areaHeight) max 0
      val visible = lines.slice(totalLines - areaHeight - scrolled, totalLines - scrolled)

      visible.zipWithIndex.foreach { case ((color, bg, text), row) =>
        tg.setForegroundColor(color)
        tg.setBackgroundColor(bg)
        tg.putString(0, 1 + row, text.take(width).padTo(width, ' '))
      }

      // Blank any rows below the content so stale lines don't linger after scroll
      tg.setForegroundColor(TextColor.ANSI.DEFAULT)
      tg.setBackgroundColor(TextColor.ANSI.DEFAULT)
      val blank = " " * width
      for (row <- visible.size until areaHeight)
        tg.putString(0, 1 + row, blank)
    }

    drawSeparator(height - 4)

    // Input prompt (row height-3) — padded so shrinking input erases old chars
    tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT)
    tg.putString(0, height - 3, (s.inputPrompt + input).take(width).padTo(width, ' '))

    drawSeparator(height - 2)

    // Mode/status bar (row height-1) — black background, dim text
    tg.setBackgroundColor(TextColor.ANSI.BLACK)
    tg.setForegroundColor(TextColor.ANSI.WHITE)
    tg.putString(0, height - 1, s.modeText.take(width).padTo(width, ' '))
  }

  /** Expand messages into displayable (foreground, background, text) lines, wrapping to `width`. */
  private def expandMessages(
    msgs:  Vector[MessageEntry],
    width: Int,
  ): Vector[(TextColor, TextColor, String)] = msgs.flatMap(expandOne(_, width))

  private def expandOne(
    m:     MessageEntry,
    width: Int,
  ): Vector[(TextColor, TextColor, String)] = {
    // P7-012: Top-level match — Raw handled first, no dead arm in inner match.
    m.kind match {
      case MessageKind.Raw =>
        // Pre-formatted banners bypass timestamp and prefix.
        // P7-032: MessageKind.Raw is a rendering detail in the domain enum — a known design trade-off.
        //         Replacing it with a `preFormatted: Boolean` field on MessageEntry would be cleaner
        //         but is deferred to avoid breaking existing callers.
        Vector((TextColor.ANSI.WHITE_BRIGHT, TextColor.ANSI.DEFAULT, m.content))
      case nonRaw =>
        val (fg, prefix) = nonRaw match {
          case MessageKind.User   => (TextColor.ANSI.WHITE_BRIGHT, "  ❯  ")
          case MessageKind.Server => (TextColor.ANSI.GREEN_BRIGHT, "  ✦  ")
          case MessageKind.System => (TextColor.ANSI.CYAN, "  ⚙  ")
          case MessageKind.Error  => (TextColor.ANSI.RED_BRIGHT, "  ✗  ")
          case MessageKind.Raw    => (TextColor.ANSI.WHITE_BRIGHT, "") // unreachable — outer arm handles Raw
        }
        val timestamp = s"[${m.time}]"
        val firstPrefix = s"$timestamp$prefix"
        val wrapIndent = " " * firstPrefix.length
        val maxFirst = width - firstPrefix.length
        val maxWrap = width - wrapIndent.length

        if (maxFirst <= 0) {
          Vector((fg, TextColor.ANSI.DEFAULT, m.content.filterNot(_ == '\r').take(width)))
        } else {
          // Split on newlines first so embedded \n from LLM responses become real line
          // breaks rather than raw control characters inside putString (which causes
          // the terminal cursor to jump mid-row and overwrite adjacent screen regions).
          val paragraphs = m.content.filterNot(_ == '\r').split('\n').toVector
          val allSegments: Vector[String] = paragraphs.zipWithIndex.flatMap { case (para, pIdx) =>
            val firstW = if (pIdx == 0) maxFirst else maxWrap
            wordWrap(para, firstW, maxWrap)
          }
          allSegments.zipWithIndex.map { case (segment, idx) =>
            val linePrefix = if (idx == 0) firstPrefix else wrapIndent
            (fg, TextColor.ANSI.DEFAULT, linePrefix + segment)
          }
        }
    }
  }

  /** Wrap `text` into lines: first line fits `firstWidth`, subsequent lines fit `wrapWidth`.
    *
    * P7-008: Tail-recursive — no mutable ArrayBuffer or var.
    */
  private def wordWrap(
    text:       String,
    firstWidth: Int,
    wrapWidth:  Int,
  ): Vector[String] = {
    val words = text.split(' ').toList

    @annotation.tailrec
    def loop(
      remaining: List[String],
      current:   String,
      maxW:      Int,
      acc:       Vector[String],
    ): Vector[String] =
      remaining match {
        case Nil =>
          if (current.isEmpty) acc else acc :+ current
        case word :: rest =>
          val w = word.take(maxW)
          if (current.isEmpty) {
            loop(rest, w, wrapWidth, acc)
          } else if (current.length + 1 + word.length <= maxW) {
            loop(rest, current + " " + word, maxW, acc)
          } else {
            loop(rest, w, wrapWidth, acc :+ current)
          }
      }

    val result = loop(words, "", firstWidth, Vector.empty)
    if (result.isEmpty) Vector("") else result
  }

}
// $COVERAGE-ON$
