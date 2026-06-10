# Jorlan Server — Homebrew formula
#
# This file is a stub. When publishing a release:
#   1. Build the macOS tarball: sbt server/universal:packageZipTarball
#   2. Update `url` and `sha256` below.
#   3. Copy this file to the rleibman/homebrew-jorlan tap repository.
#
# Users install via:
#   brew tap rleibman/jorlan
#   brew install jorlan
#   brew services start jorlan   (or: sudo brew services start jorlan for system daemon)

class Jorlan < Formula
  desc "Secure Agent Runtime and Orchestration Platform"
  homepage "https://github.com/rleibman/jorlan"
  # TODO: update url and sha256 before publishing a real release
  url "https://github.com/rleibman/jorlan/releases/download/v0.1.0/jorlan-server-0.1.0.tgz"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  license :cannot_represent
  version "0.1.0"

  depends_on "openjdk@21"

  def install
    libexec.install Dir["*"]
    (bin/"jorlan-server").write <<~SH
      #!/bin/bash
      ENV_FILE="#{etc}/jorlan/server.env"
      if [[ -f "$ENV_FILE" ]]; then
        set -a
        . "$ENV_FILE"
        set +a
      fi
      export JAVA_HOME="#{Formula["openjdk@21"].opt_prefix}"
      exec "#{libexec}/bin/jorlan-server" "$@"
    SH

    # Install env template (do not overwrite an existing one)
    (etc/"jorlan").mkpath
    env_target = etc/"jorlan/server.env"
    unless env_target.exist?
      cp libexec/"conf/server.env", env_target
    end

    # Expose init-db helper
    (bin/"jorlan-init-db").write <<~SH
      #!/bin/bash
      exec "#{libexec}/scripts/init-db.sh" "$@"
    SH
    chmod 0755, bin/"jorlan-init-db"
  end

  service do
    run [opt_bin/"jorlan-server"]
    environment_variables(
      JORLAN_ENV_FILE: etc/"jorlan/server.env",
    )
    keep_alive true
    log_path var/"log/jorlan/server.log"
    error_log_path var/"log/jorlan/server-error.log"
    working_dir opt_libexec
  end

  def caveats
    <<~EOS
      Before starting Jorlan:

        1. Set up the database (requires MariaDB and root credentials):
             jorlan-init-db --root-password <root-pw> --app-password <app-pw>

        2. Edit the env file and set JORLAN_AUTH_SECRET_KEY:
             #{etc}/jorlan/server.env

        3. Start the server:
             brew services start jorlan            # runs as your user (LaunchAgent)
             sudo brew services start jorlan       # runs at system boot (LaunchDaemon)

        4. Complete first-run setup by opening the shell:
             jorlan

        Logs: #{var}/log/jorlan/
    EOS
  end

  test do
    # Smoke test: server starts and /api/status responds
    ENV["JORLAN_AUTH_SECRET_KEY"] = "test-key-ci"
    ENV["JORLAN_DB_URL"] = "jdbc:mariadb://localhost:3306/jorlan_test"
    ENV["JORLAN_DB_USER"] = "jorlan"
    ENV["JORLAN_DB_PASSWORD"] = "jorlan"
    pid = fork { exec bin/"jorlan-server" }
    sleep 5
    system "curl", "--silent", "--fail", "http://localhost:8080/api/status"
  ensure
    Process.kill("TERM", pid) if pid
  end
end
