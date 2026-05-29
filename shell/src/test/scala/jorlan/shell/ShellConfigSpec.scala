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

object ShellConfigSpec extends ZIOSpecDefault {

  override def spec: Spec[Any, Any] =
    suite("ShellConfig")(
      suite("layer — HOCON loading")(
        test("loads defaults from application.conf when no user file exists") {
          // The application.conf in test resources has jorlan.shell.serverUrl = "http://localhost:8080"
          ZIO
            .scoped {
              ShellConfig.layer.build.map(_.get[ShellConfig])
            }.map { cfg =>
              assertTrue(cfg.serverUrl == "http://localhost:8080")
            }
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
    )

}
