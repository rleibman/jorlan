/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.web.pages

import caliban.WebSocketHandler
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.*
import jorlan.web.AsyncCallbackRepositories
import jorlan.web.components.{MuiButton, MuiTextField}
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}

import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

import scala.language.unsafeNulls
import scala.scalajs.js

object ChatPage {

  case class ChatMessage(
    role:    String,
    content: String,
    ts:      String,
  )

  case class State(
    sessionId:    Option[AgentSessionId],
    input:        String,
    messages:     List[ChatMessage],
    streaming:    Boolean,
    streamBuffer: String,
    wsHandler:    Option[WebSocketHandler],
    error:        Option[String],
    pendingQueue: List[String],
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(
        State(None, "", List.empty, streaming = false, "", None, error = None, pendingQueue = List.empty),
      )
      .useRef(Option.empty[WebSocketHandler])
      .useRef(List.empty[String]) // queueRef: FIFO queue readable from stale onData closures
      .useEffectOnMountBy {
        (
          _,
          state,
          handlerRef,
          queueRef,
        ) =>
          // Reconnect to an existing active session, or create a new one if none found
          Callback {
            AsyncCallbackRepositories.agent
              .searchSessions(AgentSessionSearch())
              .flatMap { sessions =>
                val existing = sessions.find(_.status == SessionStatus.Active)
                val isReconnect = existing.isDefined
                val sessionAC =
                  existing.fold(AsyncCallbackRepositories.agent.createSession(None))(s => AsyncCallback.pure(Some(s)))
                sessionAC.flatMap {
                  case Some(session) =>
                    val label = if (isReconnect) "Reconnected to existing session" else "Session started"
                    state
                      .modState(
                        _.copy(
                          sessionId = Some(session.id),
                          messages = scala.List(ChatMessage("system", label, new js.Date().toISOString())),
                        ),
                      )
                      .asAsyncCallback
                      .flatMap { _ =>
                        Callback {
                          state.value.wsHandler.foreach(_.close().runNow())
                          val sid = session.id
                          val handler = AsyncCallbackRepositories.subscribeToAgentStream(
                            session.id,
                            onClientError =
                              ex => state.modState(_.copy(streaming = false, error = Some(ex.getMessage))),
                            onData = { chunk =>
                              if (chunk.finished) {
                                // Pop the next queued message before updating state
                                val nextMsgOpt = queueRef.value.headOption
                                val popRef = nextMsgOpt.fold(Callback.empty)(_ => queueRef.mod(_.tail))
                                popRef >>
                                  state.modState { s =>
                                    s.copy(
                                      messages = s.messages :+
                                        ChatMessage(
                                          if (chunk.isError) "error" else "assistant",
                                          s.streamBuffer + chunk.content,
                                          new js.Date().toISOString(),
                                        ),
                                      streaming = nextMsgOpt.isDefined,
                                      streamBuffer = "",
                                      pendingQueue =
                                        if (nextMsgOpt.isDefined) s.pendingQueue.drop(1) else s.pendingQueue,
                                    )
                                  } >> nextMsgOpt.fold(Callback.empty) { nextMsg =>
                                    Callback {
                                      AsyncCallbackRepositories.agent
                                        .submitMessage(sid, nextMsg)
                                        .completeWith(
                                          PageUtils.onError(err =>
                                            state.modState(_.copy(streaming = false, error = err)),
                                          ),
                                        )
                                        .runNow()
                                    }
                                  }
                              } else {
                                state.modState(s =>
                                  s.copy(streaming = true, streamBuffer = s.streamBuffer + chunk.content),
                                )
                              }
                            },
                          )
                          handlerRef.set(Some(handler)).runNow()
                          state.modState(_.copy(wsHandler = Some(handler))).runNow()
                        }.asAsyncCallback
                      }
                  case None =>
                    state
                      .modState(s =>
                        s.copy(
                          messages = s.messages :+ ChatMessage(
                            "error",
                            "Failed to create session",
                            new js.Date().toISOString(),
                          ),
                        ),
                      )
                      .asAsyncCallback
                }
              }
              .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
              .runNow()
          }
      }
      .render {
        (
          _,
          state,
          _,
          queueRef,
        ) =>
          def sendMessage(): Callback = {
            val text = state.value.input.trim
            if (text.isEmpty || state.value.sessionId.isEmpty) Callback.empty
            else if (state.value.streaming) {
              // Enqueue: update ref (for callback reads) and state (for display)
              queueRef.mod(_ :+ text) >>
                state.modState(s =>
                  s.copy(
                    input = "",
                    messages = s.messages :+ ChatMessage("user", text, new js.Date().toISOString()),
                    pendingQueue = s.pendingQueue :+ text,
                  ),
                )
            } else {
              state.modState(s =>
                s.copy(
                  input = "",
                  messages = s.messages :+ ChatMessage("user", text, new js.Date().toISOString()),
                  streaming = true,
                ),
              ) >>
                state.value.sessionId.fold(Callback.empty) { sessionId =>
                  Callback {
                    AsyncCallbackRepositories.agent
                      .submitMessage(sessionId, text)
                      .completeWith(
                        PageUtils.onError(err => state.modState(_.copy(streaming = false, error = err))),
                      )
                      .runNow()
                  }
                }
            }
          }

          def handleKeyDown(e: js.Dynamic): Unit = {
            val key = e.key.asInstanceOf[String]
            val shift = e.shiftKey.asInstanceOf[Boolean]
            if (key == "Enter" && !shift && state.value.sessionId.isDefined) {
              e.preventDefault()
              sendMessage().runNow()
            }
          }

          val queueCount = state.value.pendingQueue.size

          <.div(
            ^.style := js.Dynamic.literal(height = "calc(100vh - 128px)", display = "flex", flexDirection = "column"),
            state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(display = "flex", alignItems = "center", mb = 1, gap = 1).asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Typography.withProps(TypographyOwnProps().setVariant("h5").asInstanceOf[Typography.Props])("Chat"),
              state.value.sessionId.fold[VdomNode](
                <.span("Connecting…"),
              )(id => <.span(s"Session: ${id.value}")),
            ),
            Box.withProps(
              js.Dynamic
                .literal(
                  sx = js.Dynamic
                    .literal(
                      flex = 1,
                      overflow = "auto",
                      border = "1px solid",
                      borderColor = "divider",
                      borderRadius = 1,
                      p = 1,
                      mb = 1,
                      fontFamily = "monospace",
                      fontSize = "0.85rem",
                    )
                    .asInstanceOf[SxProps[Theme]],
                  `aria-live` = "polite",
                  `aria-atomic` = "false",
                )
                .asInstanceOf[Box.Props],
            )(
              React.Fragment(
                state.value.messages.zipWithIndex.map { case (msg, i) =>
                  val (prefix, color) = msg.role match {
                    case "user"   => ("❯", "#2563eb")
                    case "system" => ("⚙", "#6b7280")
                    case "error"  => ("✗", "#ef4444")
                    case _        => ("✦", "#7c3aed")
                  }
                  <.div(
                    ^.key   := i.toString,
                    ^.style := js.Dynamic.literal(marginBottom = "4px"),
                    <.span(^.style := js.Dynamic.literal(color = "#9ca3af", fontSize = "0.75rem"))(msg.ts.take(19)),
                    " ",
                    <.span(^.style := js.Dynamic.literal(color = color))(prefix),
                    " ",
                    <.span(^.style := js.Dynamic.literal(whiteSpace = "pre-wrap"))(msg.content),
                  )
                }*,
              ),
              if (state.value.streaming && state.value.streamBuffer.nonEmpty)
                <.div(
                  ^.style := js.Dynamic.literal(whiteSpace = "pre-wrap"),
                  <.span(^.style := js.Dynamic.literal(color = "#7c3aed"))("✦"),
                  " ",
                  state.value.streamBuffer,
                  <.span("▊"),
                )
              else EmptyVdom,
            ),
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(js.Dynamic.literal(display = "flex", gap = 1).asInstanceOf[SxProps[Theme]]).asInstanceOf[
                  Box.Props,
                ],
            )(
              MuiTextField
                .value(state.value.input)
                .fullWidth(true)
                .multiline(true)
                .maxRows(6)
                .placeholder("Type a message… (Enter to send, Shift+Enter for newline)")
                .variant("outlined")
                .size("small")
                .onChange(e => state.modState(_.copy(input = e.target.value.asInstanceOf[String])).runNow())
                .onKeyDown(handleKeyDown)
                .disabled(state.value.sessionId.isEmpty),
              MuiButton
                .variant("contained")
                .disabled(state.value.input.trim.isEmpty || state.value.sessionId.isEmpty)
                .onClick(() => sendMessage().runNow())(
                  if (queueCount > 0) s"Queue ($queueCount)" else "Send",
                ),
            ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
