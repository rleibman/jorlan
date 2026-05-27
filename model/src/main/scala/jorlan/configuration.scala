/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan

case class HttpConfig(
  host: String = "0.0.0.0",
  port: Int = 8080,
)

/** Raw config for a single OAuth 2.0 provider, read from `application.conf`. Converted to
  * `auth.oauth.OAuthProviderConfig` during environment assembly.
  */
case class OAuthProviderSettings(
  clientId:         String,
  clientSecret:     String,
  authorizationUri: String,
  tokenUri:         String,
  userInfoUri:      String,
  redirectUri:      String,
  scopes:           List[String],
)

/** Authentication and session configuration. */
case class AuthSettings(
  secretKey:        String,
  accessTtlMinutes: Int = 60,
  refreshTtlDays:   Int = 30,
  google:           Option[OAuthProviderSettings] = None,
  github:           Option[OAuthProviderSettings] = None,
  discord:          Option[OAuthProviderSettings] = None,
)
