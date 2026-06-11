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

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.domain.*
import jorlan.web.JorlanWebApp
import jorlan.web.components.{MuiButton, MuiTextField}
import jorlan.web.graphql.WebSocketHandler
import jorlan.web.graphql.client.JorlanClient
import jorlan.web.graphql.client.JorlanClientDecoders._
import net.leibman.jorlan.muiMaterial.components.*

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
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(None, "", Nil, streaming = false, "", None, error = None))
      .useRef(Option.empty[WebSocketHandler])
      .useEffectOnMountBy {
        (
          _,
          _,
          handlerRef,
        ) =>
          CallbackTo {
            // cleanup: close any active subscription when the component unmounts
            handlerRef.get.flatMap(_.fold(Callback.empty)(_.close()))
          }
      }
      .render {
        (
          _,
          state,
          handlerRef,
        ) =>
          def openStreamSubscription(sessionId: AgentSessionId): Callback =
            Callback {
              // Close any existing stream subscription before opening a new one
              state.value.wsHandler.foreach(_.close().runNow())
              val handler = JorlanWebApp
                .makeAdapter().makeWebSocketClient(
                  webSocket = None,
                  query = JorlanClient.Subscriptions
                    .agentResponseStream(sessionId)(JorlanClient.ResponseChunk.view),
                  operationId = s"chat-stream-${sessionId.value}",
                  socketConnectionId = s"chat-${sessionId.value}",
                  onData = {
                    (
                      _,
                      dataOpt,
                    ) =>
                      dataOpt.flatten.fold(Callback.empty) { chunk =>
                        if (chunk.finished) {
                          // Flush the completed response into the messages list
                          val completedMsg = ChatMessage(
                            if (chunk.isError) "error" else "assistant",
                            state.value.streamBuffer + chunk.content,
                            new js.Date().toISOString(),
                          )
                          state.setState(
                            state.value.copy(
                              messages = state.value.messages :+ completedMsg,
                              streaming = false,
                              streamBuffer = "",
                            ),
                          )
                        } else {
                          state.setState(
                            state.value.copy(
                              streaming = true,
                              streamBuffer = state.value.streamBuffer + chunk.content,
                            ),
                          )
                        }
                      }
                  },
                )
              handlerRef.set(Some(handler)).runNow()
              state.setState(state.value.copy(wsHandler = Some(handler))).runNow()
            }

          def sendMessage(): Callback = {
            val text = state.value.input.trim
            if (text.isEmpty || state.value.sessionId.isEmpty) Callback.empty
            else {
              state.setState(
                state.value.copy(
                  input = "",
                  messages = state.value.messages :+ ChatMessage("user", text, new js.Date().toISOString()),
                  streaming = true,
                ),
              ) >>
                state.value.sessionId.fold(Callback.empty) { sessionId =>
                  Callback {
                    JorlanWebApp
                      .makeAdapter()
                      .asyncCalibanCallWithAuth(
                        JorlanClient.Mutations.submitMessage(sessionId, text),
                      )
                      .completeWith {
                        case scala.util.Failure(ex) =>
                          state.setState(state.value.copy(streaming = false, error = Some(ex.getMessage)))
                        case _ => Callback.empty
                      }
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

          def createSession(): Callback =
            Callback {
              JorlanWebApp
                .makeAdapter()
                .asyncCalibanCallWithAuth(
                  JorlanClient.Mutations.createSession(None)(JorlanClient.AgentSession.view),
                )
                .flatMap {
                  case Some(session) =>
                    (state
                      .setState(
                        state.value.copy(
                          sessionId = Some(session.id),
                          messages = scala.List(ChatMessage("system", "Session started", new js.Date().toISOString())),
                        ),
                      ) >> openStreamSubscription(session.id)).asAsyncCallback
                  case None =>
                    state
                      .setState(
                        state.value.copy(
                          messages = state.value.messages :+ ChatMessage(
                            "error",
                            "Failed to create session",
                            new js.Date().toISOString(),
                          ),
                        ),
                      )
                      .asAsyncCallback
                }
                .completeWith {
                  case scala.util.Failure(ex) => state.setState(state.value.copy(error = Some(ex.getMessage)))
                  case _                      => Callback.empty
                }
                .runNow()
            }

          <.div(
            ^.style := js.Dynamic.literal(height = "calc(100vh - 128px)", display = "flex", flexDirection = "column"),
            state.value.error.fold(EmptyVdom)(err => Alert.set("severity", "error")(err)),
            Box.set("sx", js.Dynamic.literal(display = "flex", alignItems = "center", mb = 1, gap = 1))(
              Typography.set("variant", "h5")("Chat"),
              state.value.sessionId.fold[VdomNode](
                MuiButton.variant("contained").onClick(() => createSession().runNow())("New Session"),
              )(id => <.span(s"Session: ${id.value}")),
            ),
            Box
              .set(
                "sx",
                js.Dynamic.literal(
                  flex = 1,
                  overflow = "auto",
                  border = "1px solid",
                  borderColor = "divider",
                  borderRadius = 1,
                  p = 1,
                  mb = 1,
                  fontFamily = "monospace",
                  fontSize = "0.85rem",
                ),
              )
              .set("aria-live", "polite")
              .set("aria-atomic", "false")(
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
                      msg.content,
                    )
                  }*,
                ),
                if (state.value.streaming && state.value.streamBuffer.nonEmpty)
                  <.div(
                    <.span(^.style := js.Dynamic.literal(color = "#7c3aed"))("✦"),
                    " ",
                    state.value.streamBuffer,
                    <.span("▊"),
                  )
                else EmptyVdom,
              ),
            Box.set("sx", js.Dynamic.literal(display = "flex", gap = 1))(
              MuiTextField
                .value(state.value.input)
                .fullWidth(true)
                .multiline(true)
                .maxRows(6)
                .placeholder("Type a message… (Enter to send, Shift+Enter for newline)")
                .variant("outlined")
                .size("small")
                .onChange(e => state.setState(state.value.copy(input = e.target.value.asInstanceOf[String])).runNow())
                .onKeyDown(handleKeyDown)
                .disabled(state.value.streaming),
              MuiButton
                .variant("contained")
                .disabled(state.value.input.trim.isEmpty || state.value.streaming || state.value.sessionId.isEmpty)
                .onClick(() => sendMessage().runNow())(
                  "Send",
                ),
            ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
