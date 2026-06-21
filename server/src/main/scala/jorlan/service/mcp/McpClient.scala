/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service.mcp

import jorlan.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.process.{Command, CommandError, ProcessInput}
import zio.stream.ZStream

/** A single tool exposed by an MCP server. */
case class McpTool(
  name:        String,
  description: Option[String],
  inputSchema: Json,
)

/** Client interface for an MCP server connection. */
trait McpClient {

  def listTools: IO[JorlanError, List[McpTool]]
  def callTool(
    name:      String,
    arguments: Json,
  ): IO[JorlanError, String]

}

// ─── HTTP MCP client ─────────────────────────────────────────────────────────

/** MCP client communicating via HTTP JSON-RPC 2.0 POST. */
class HttpMcpClient private (
  httpClient:  Client,
  url:         String,
  idRef:       Ref[Int],
  initialized: Ref[Boolean],
) extends McpClient {

  private def nextId: UIO[Int] = idRef.getAndUpdate(_ + 1)

  private def sendRequest(
    method: String,
    params: Option[Json],
  ): IO[JorlanError, Json] = {
    for {
      id <- nextId
      reqObj = {
        val base = List(
          "jsonrpc" -> Json.Str("2.0"),
          "id"      -> Json.Num(id),
          "method"  -> Json.Str(method),
        )
        val withParams = params.fold(base)(p => base :+ ("params" -> p))
        Json.Obj(withParams*)
      }
      reqBody = reqObj.toJson
      parsedUrl <- ZIO
        .fromEither(URL.decode(url))
        .mapError(e => JorlanError(s"MCP HTTP: invalid URL '$url': $e"))
      resp <- httpClient
        .batched(
          Request
            .post(parsedUrl, Body.fromString(reqBody, java.nio.charset.Charset.forName("UTF-8")))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        .mapError(e => JorlanError(s"MCP HTTP request failed: ${e.getMessage}", Some(e)))
      body <- resp.body.asString
        .mapError(e => JorlanError(s"MCP HTTP: failed to read response body: ${e.getMessage}", Some(e)))
      _ <- ZIO.when(!resp.status.isSuccess)(
        ZIO.fail(JorlanError(s"MCP HTTP ${resp.status.code}: $body")),
      )
      json <- ZIO
        .fromEither(Json.decoder.decodeJson(body))
        .mapError(e => JorlanError(s"MCP HTTP: bad JSON response: $e"))
      result <- json match {
        case Json.Obj(fields) =>
          fields.collectFirst { case ("error", errObj) => errObj } match {
            case Some(Json.Obj(errFields)) =>
              val msg = errFields.collectFirst { case ("message", Json.Str(m)) => m }.getOrElse("unknown error")
              ZIO.fail(JorlanError(s"MCP server error: $msg"))
            case _ =>
              fields.collectFirst { case ("result", r) => r } match {
                case Some(r) => ZIO.succeed(r)
                case None    => ZIO.fail(JorlanError(s"MCP HTTP: response has no 'result' field: $body"))
              }
          }
        case _ => ZIO.fail(JorlanError(s"MCP HTTP: unexpected response shape: $body"))
      }
    } yield result
  }

  private def ensureInitialized: IO[JorlanError, Unit] = {
    ZIO
      .unlessZIO(initialized.get) {
        for {
          _ <- sendRequest(
            "initialize",
            Some(
              Json.Obj(
                "protocolVersion" -> Json.Str("2024-11-05"),
                "capabilities"    -> Json.Obj(),
                "clientInfo"      -> Json.Obj(
                  "name"    -> Json.Str("jorlan"),
                  "version" -> Json.Str("1.0"),
                ),
              ),
            ),
          )
          // Send initialized notification (fire-and-forget, best-effort)
          notifBody = Json
            .Obj(
              "jsonrpc" -> Json.Str("2.0"),
              "method"  -> Json.Str("notifications/initialized"),
            ).toJson
          _ <- ZIO
            .fromEither(URL.decode(url))
            .flatMap { parsedUrl =>
              httpClient
                .batched(
                  Request
                    .post(parsedUrl, Body.fromString(notifBody, java.nio.charset.Charset.forName("UTF-8")))
                    .addHeader(Header.ContentType(MediaType.application.json)),
                )
            }
            .ignore
          _ <- initialized.set(true)
        } yield ()
      }.unit
  }

  override def listTools: IO[JorlanError, List[McpTool]] = {
    for {
      _     <- ensureInitialized
      raw   <- sendRequest("tools/list", None)
      tools <- parseToolsList(raw)
    } yield tools
  }

  override def callTool(
    name:      String,
    arguments: Json,
  ): IO[JorlanError, String] = {
    for {
      _   <- ensureInitialized
      raw <- sendRequest(
        "tools/call",
        Some(
          Json.Obj(
            "name"      -> Json.Str(name),
            "arguments" -> arguments,
          ),
        ),
      )
      result <- parseCallResult(raw)
    } yield result
  }

}

object HttpMcpClient {

  def make(
    httpClient: Client,
    url:        String,
  ): UIO[HttpMcpClient] = {
    for {
      idRef   <- Ref.make(1)
      initRef <- Ref.make(false)
    } yield new HttpMcpClient(httpClient, url, idRef, initRef)
  }

}

// ─── Stdio MCP client ────────────────────────────────────────────────────────

/** MCP client communicating via stdin/stdout of a subprocess. */
class StdioMcpClient private (
  stdinQueue:  Queue[Chunk[Byte]],
  outputQueue: Queue[String],
  idRef:       Ref[Int],
  semaphore:   Semaphore,
) extends McpClient {

  private def nextId: UIO[Int] = idRef.getAndUpdate(_ + 1)

  private def writeRequest(json: String): UIO[Unit] =
    stdinQueue.offer(Chunk.fromArray((json + "\n").getBytes("UTF-8"))).unit

  private def readResponse: IO[JorlanError, Json] =
    outputQueue.take.flatMap { line =>
      ZIO
        .fromEither(Json.decoder.decodeJson(line))
        .mapError(e => JorlanError(s"MCP stdio: bad JSON response: $e — raw: $line"))
    }

  private def sendRequest(json: String): IO[JorlanError, Json] = {
    semaphore.withPermit {
      writeRequest(json) *> readResponse.flatMap { resp =>
        resp match {
          case Json.Obj(fields) =>
            fields.collectFirst { case ("error", errObj) => errObj } match {
              case Some(Json.Obj(errFields)) =>
                val msg = errFields.collectFirst { case ("message", Json.Str(m)) => m }.getOrElse("unknown error")
                ZIO.fail(JorlanError(s"MCP server error: $msg"))
              case _ =>
                fields.collectFirst { case ("result", r) => r } match {
                  case Some(r) => ZIO.succeed(r)
                  case None    => ZIO.fail(JorlanError(s"MCP stdio: response has no 'result' field"))
                }
            }
          case _ => ZIO.fail(JorlanError(s"MCP stdio: unexpected response shape"))
        }
      }
    }
  }

  override def listTools: IO[JorlanError, List[McpTool]] = {
    for {
      id <- nextId
      req = s"""{"jsonrpc":"2.0","id":$id,"method":"tools/list"}"""
      raw   <- sendRequest(req)
      tools <- parseToolsList(raw)
    } yield tools
  }

  override def callTool(
    name:      String,
    arguments: Json,
  ): IO[JorlanError, String] = {
    for {
      id <- nextId
      paramsJson = Json
        .Obj(
          "name"      -> Json.Str(name),
          "arguments" -> arguments,
        ).toJson
      req = s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":$paramsJson}"""
      raw    <- sendRequest(req)
      result <- parseCallResult(raw)
    } yield result
  }

}

object StdioMcpClient {

  def make(config: McpServerConfig): ZIO[Scope, JorlanError, StdioMcpClient] = {
    for {
      stdinQ  <- Queue.unbounded[Chunk[Byte]]
      outputQ <- Queue.unbounded[String]
      command <- ZIO
        .fromOption(config.command)
        .orElseFail(JorlanError(s"MCP server '${config.name}': stdio transport requires 'command'"))
      cmdParts = command.split("\\s+").toList.filter(_.nonEmpty)
      cmdName <- cmdParts.headOption match {
        case Some(n) => ZIO.succeed(n)
        case None    => ZIO.fail(JorlanError(s"MCP server '${config.name}': command is empty"))
      }
      cmdArgs = cmdParts.drop(1) ++ config.args
      baseCmd = Command(cmdName, cmdArgs*)
      cmdWithEnv = if (config.env.isEmpty) baseCmd else baseCmd.env(config.env)
      cmdWithStdin = cmdWithEnv.stdin(ProcessInput.fromQueue(stdinQ))
      process <- ZIO.acquireRelease(
        cmdWithStdin.run.mapError(e => JorlanError(s"MCP stdio: failed to start process: ${e.getMessage}", Some(e))),
      )(proc => proc.isAlive.flatMap(alive => ZIO.when(alive)(proc.killForcibly)).orDie)
      // Fork a fiber to drain stdout lines into outputQueue
      _ <- ZIO.acquireRelease(
        process.stdout.linesStream
          .mapError(e => JorlanError(s"MCP stdio: stdout error: ${e.getMessage}", Some(e)))
          .foreach(line => outputQ.offer(line).unit)
          .forkDaemon,
      )(_.interrupt)
      idRef     <- Ref.make(1)
      semaphore <- Semaphore.make(1)
      client = new StdioMcpClient(stdinQ, outputQ, idRef, semaphore)
      initId <- idRef.getAndUpdate(_ + 1)
      initReq =
        s"""{"jsonrpc":"2.0","id":$initId,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"jorlan","version":"1.0"}}}"""
      _ <- semaphore.withPermit {
        client.writeRequest(initReq) *> client.readResponse.flatMap {
          case Json.Obj(fields) =>
            fields.collectFirst { case ("error", Json.Obj(errFields)) =>
              errFields.collectFirst { case ("message", Json.Str(m)) => m }.getOrElse("unknown error")
            } match {
              case Some(msg) => ZIO.fail(JorlanError(s"MCP server error: $msg"))
              case None      =>
                fields.collectFirst { case ("result", _) => () } match {
                  case Some(_) => ZIO.unit
                  case None    => ZIO.fail(JorlanError("MCP stdio: initialize response has no 'result' field"))
                }
            }
          case _ => ZIO.fail(JorlanError("MCP stdio: initialize returned unexpected response shape"))
        }
      }
      // Send initialized notification (no response expected)
      notifReq = s"""{"jsonrpc":"2.0","method":"notifications/initialized"}"""
      _ <- client.writeRequest(notifReq)
    } yield client
  }

}

// ─── Shared response parsing ─────────────────────────────────────────────────

private[mcp] def parseToolsList(result: Json): IO[JorlanError, List[McpTool]] = {
  result match {
    case Json.Obj(fields) =>
      fields.collectFirst { case ("tools", Json.Arr(toolsArr)) => toolsArr } match {
        case None        => ZIO.succeed(List.empty)
        case Some(tools) =>
          ZIO.foreach(tools.toList) {
            case Json.Obj(toolFields) =>
              val name = toolFields.collectFirst { case ("name", Json.Str(n)) if n.nonEmpty => n }
              val desc = toolFields.collectFirst { case ("description", Json.Str(d)) => d }
              val schema = toolFields.collectFirst { case ("inputSchema", s) => s }.getOrElse(Json.Obj())
              name match {
                case Some(n) => ZIO.succeed(McpTool(n, desc, schema))
                case None    =>
                  ZIO.fail(JorlanError(s"MCP: tool entry missing non-empty 'name': ${Json.Obj(toolFields*).toJson}"))
              }
            case other =>
              ZIO.fail(JorlanError(s"MCP: unexpected tool shape: $other"))
          }
      }
    case _ => ZIO.succeed(List.empty)
  }
}

private[mcp] def parseCallResult(result: Json): IO[JorlanError, String] = {
  result match {
    case Json.Obj(fields) =>
      // Check isError flag
      val isError = fields.collectFirst { case ("isError", Json.Bool(b)) => b }.getOrElse(false)
      val content = fields.collectFirst { case ("content", Json.Arr(items)) => items }.getOrElse(Chunk.empty)
      val textParts = content.collect { case Json.Obj(itemFields) =>
        itemFields.collectFirst { case ("text", Json.Str(t)) => t }.getOrElse("")
      }
      val combined = textParts.mkString("\n")
      if (isError) ZIO.fail(JorlanError(s"MCP tool returned error: $combined"))
      else ZIO.succeed(combined)
    case other =>
      ZIO.succeed(other.toJson)
  }
}
