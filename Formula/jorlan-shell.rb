# Jorlan Shell — Homebrew formula
#
# This file is a stub. When publishing a release:
#   1. Build the macOS tarball: sbt shell/universal:packageZipTarball
#   2. Update `url` and `sha256` below.
#   3. Copy this file to the rleibman/homebrew-jorlan tap repository.
#
# Users install via:
#   brew tap rleibman/jorlan
#   brew install jorlan-shell

class JorlanShell < Formula
  desc "Shell client for the Jorlan Secure Agent Runtime"
  homepage "https://github.com/rleibman/jorlan"
  # TODO: update url and sha256 before publishing a real release
  url "https://github.com/rleibman/jorlan/releases/download/v0.1.0/jorlan-shell-0.1.0.tgz"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  license :cannot_represent
  version "0.1.0"

  depends_on "openjdk@21"

  def install
    libexec.install Dir["*"]
    (bin/"jorlan").write <<~SH
      #!/bin/bash
      export JAVA_HOME="#{Formula["openjdk@21"].opt_prefix}"
      exec "#{libexec}/bin/jorlan" "$@"
    SH
  end

  def caveats
    <<~EOS
      Run the Jorlan shell:
        jorlan

      On first launch, the shell will prompt you for the server URL and
      will walk you through first-run setup if the server is uninitialized.

      Config is stored in ~/.jorlan/jorlan-shell.json after first-run.
    EOS
  end

  test do
    assert_match "Jorlan", shell_output("#{bin}/jorlan --version 2>&1", 1)
  end
end
