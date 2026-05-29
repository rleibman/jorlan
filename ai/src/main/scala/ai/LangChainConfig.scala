/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package ai
// $COVERAGE-OFF$

case class LangChainConfig(
  ollamaBaseUrl: String = "http://localhost:11434",
  ollamaModel:   String = "llama3.2:3b",
  qdrantHost:    String = "localhost",
  qdrantRPCPort: Int = 6334,
)
// $COVERAGE-ON$
