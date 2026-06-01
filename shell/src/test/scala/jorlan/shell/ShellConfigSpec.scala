/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell

import com.typesafe.config.ConfigFactory
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.test.*
import zio.test.Assertion.*

import java.io.File
import java.nio.file.Files

object ShellConfigSpec extends ZIOSpecDefault {

  private def tmpFile: UIO[File] =
    ZIO.succeed(Files.createTempFile("jorlan-shellcfg-", ".json").toFile).map { f => f.delete(); f }

  override def spec: Spec[Any, Any] =
    suite("ShellConfig")(
      suite("layer — HOCON loading")(
        test("loads defaults from application.conf when no user file exists") {
          // The application.conf in test resources has jorlan.shell.serverUrl = "http://localhost:8080"
          ZIO
            .scoped {
              (ZLayer.succeed(ZIOAppArgs(Chunk.empty)) >>> ShellConfig.layer).build
                .map(_.get[ShellConfig])
            }.map { cfg =>
              assertTrue(cfg.serverUrl == "http://localhost:8080")
            }
        },
        test("--config flag is honored by layer when the file exists") {
          for {
            f   <- tmpFile
            cfg  = ShellConfig("http://from-file:7777", Some("cfg@test.com"), None)
            _   <- ShellConfig.write(f, cfg)
            loaded <- ZIO.scoped {
              (ZLayer.succeed(ZIOAppArgs(Chunk("--config", f.getAbsolutePath))) >>> ShellConfig.layer)
                .build
                .map(_.get[ShellConfig])
            }
          } yield assertTrue(loaded.serverUrl == "http://from-file:7777")
        },
        test("overrides values from a HOCON string") {
          val hocon =
            """jorlan.shell {
              |  serverUrl = "http://custom:9999"
              |  email = "user@example.com"
              |  password = "secret"
              |}""".stripMargin
          val provider = TypesafeConfigProvider.fromTypesafeConfig(
            ConfigFactory.parseString(hocon).withFallback(ConfigFactory.load()).resolve(),
          )
          val descriptor = zio.config.magnolia.DeriveConfig.derived[ShellConfig].desc.nested("jorlan", "shell")
          provider.load(descriptor).map { cfg =>
            assertTrue(
              cfg.serverUrl == "http://custom:9999",
              cfg.email.contains("user@example.com"),
              cfg.password.contains("secret"),
            )
          }
        },
        test("email and password fall back to Some(\"\") from application.conf defaults") {
          // application.conf has `email = ""` and `password = ""` as defaults.
          // filter(_.nonEmpty) in resolveCredentials treats these as absent at runtime.
          val hocon = """jorlan.shell { serverUrl = "http://localhost:8080" }"""
          val provider = TypesafeConfigProvider.fromTypesafeConfig(
            ConfigFactory.parseString(hocon).withFallback(ConfigFactory.load()).resolve(),
          )
          val descriptor = zio.config.magnolia.DeriveConfig.derived[ShellConfig].desc.nested("jorlan", "shell")
          provider.load(descriptor).map { cfg =>
            assertTrue(cfg.email.contains("") && cfg.password.contains(""))
          }
        },
      ),
      suite("applyArgs")(
        test("no args leaves config unchanged") {
          val cfg = ShellConfig()
          assertTrue(ShellConfig.applyArgs(cfg, Nil) == cfg)
        },
        test("--server-url overrides serverUrl") {
          val cfg = ShellConfig()
          val result = ShellConfig.applyArgs(cfg, List("--server-url", "http://remote:9090"))
          assertTrue(result.serverUrl == "http://remote:9090")
        },
        test("--email overrides email") {
          val cfg = ShellConfig()
          val result = ShellConfig.applyArgs(cfg, List("--email", "user@example.com"))
          assertTrue(result.email.contains("user@example.com"))
        },
        test("--password overrides password") {
          val cfg = ShellConfig()
          val result = ShellConfig.applyArgs(cfg, List("--password", "secret"))
          assertTrue(result.password.contains("secret"))
        },
        test("multiple args applied in order") {
          val cfg = ShellConfig()
          val result = ShellConfig.applyArgs(
            cfg,
            List("--server-url", "http://a:1", "--email", "a@b.com", "--password", "pw"),
          )
          assertTrue(
            result.serverUrl == "http://a:1",
            result.email.contains("a@b.com"),
            result.password.contains("pw"),
          )
        },
        test("unknown args ignored") {
          val cfg = ShellConfig()
          val result = ShellConfig.applyArgs(cfg, List("--unknown", "value"))
          assertTrue(result == cfg)
        },
        // P7-042: Flags at end of list with no value are silently dropped — document the intent.
        test("--server-url at end of list with no value is silently ignored") {
          val cfg = ShellConfig()
          val result = ShellConfig.applyArgs(cfg, List("--server-url"))
          assertTrue(result.serverUrl == cfg.serverUrl)
        },
        test("--email at end of list with no value is silently ignored") {
          val cfg = ShellConfig()
          val result = ShellConfig.applyArgs(cfg, List("--email"))
          assertTrue(result.email == cfg.email)
        },
        test("--password at end of list with no value is silently ignored") {
          val cfg = ShellConfig()
          val result = ShellConfig.applyArgs(cfg, List("--password"))
          assertTrue(result.password == cfg.password)
        },
      ),
      // P8.1-005: resolveWritePath, isFirstRun, write, findReadFile
      suite("resolveWritePath")(
        test("returns default path when no args and no env var") {
          ShellConfig.resolveWritePath(Nil).map { file =>
            assertTrue(file.getPath.endsWith("jorlan-shell.json"))
          }
        },
        test("--config arg overrides default") {
          ShellConfig.resolveWritePath(List("--config", "/tmp/custom.json")).map { file =>
            assertTrue(file.getPath == "/tmp/custom.json")
          }
        },
      ),
      suite("isFirstRun")(
        test("returns false when a config file exists and serverUrl is non-empty") {
          for {
            f <- tmpFile
            _ <- ZIO.attempt(f.createNewFile())
            cfg = ShellConfig(serverUrl = "http://localhost:8080")
            result <- ShellConfig.isFirstRun(cfg, List("--config", f.getAbsolutePath))
          } yield assertTrue(!result)
        },
        test("returns true when serverUrl is empty even if file exists") {
          for {
            f <- tmpFile
            _ <- ZIO.attempt(f.createNewFile())
            cfg = ShellConfig(serverUrl = "")
            result <- ShellConfig.isFirstRun(cfg, List("--config", f.getAbsolutePath))
          } yield assertTrue(result)
        },
      ),
      suite("write and round-trip")(
        test("write then load via HOCON produces the original config") {
          for {
            f <- tmpFile
            cfg = ShellConfig("http://roundtrip:9090", Some("user@example.com"), Some("s3cr3t!"))
            _      <- ShellConfig.write(f, cfg)
            loaded <- ZIO.attempt {
              val typesafeConfig = ConfigFactory.parseFile(f).withFallback(ConfigFactory.load()).resolve()
              val provider = TypesafeConfigProvider.fromTypesafeConfig(typesafeConfig)
              val descriptor = zio.config.magnolia.DeriveConfig.derived[ShellConfig].desc.nested("jorlan", "shell")
              zio.Runtime.default.unsafe.run(provider.load(descriptor)).getOrThrow()
            }
          } yield assertTrue(
            loaded.serverUrl == cfg.serverUrl,
            loaded.email == cfg.email,
            loaded.password == cfg.password,
          )
        },
        test("write creates parent directories if absent") {
          for {
            base <- ZIO.succeed(Files.createTempDirectory("jorlan-test-").toFile)
            deep = new File(base, "nested/deep/config.json")
            cfg = ShellConfig("http://localhost:8080")
            _ <- ShellConfig.write(deep, cfg)
          } yield assertTrue(deep.exists())
        },
      ),
      suite("findReadFile")(
        test("returns the --config file when it exists") {
          for {
            f      <- tmpFile
            _      <- ZIO.attempt(f.createNewFile())
            result <- ShellConfig.findReadFile(List("--config", f.getAbsolutePath))
          } yield assertTrue(result.contains(f))
        },
        test("does not return a --config file that does not exist") {
          for {
            result <- ShellConfig.findReadFile(List("--config", "/tmp/nonexistent-jorlan-xyz-abc.json"))
          } yield assertTrue(!result.exists(_.getPath == "/tmp/nonexistent-jorlan-xyz-abc.json"))
        },
      ),
    )

}
