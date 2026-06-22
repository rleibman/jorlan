/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.search

import zio.json.JsonCodec

case class SearchConfig(
  apiKey:     String = "",
  baseUrl:    String = "https://api.tavily.com",
  maxResults: Int = 5,
) derives JsonCodec
