import { Github, BookOpen } from 'lucide-react';

const GITHUB_URL = 'https://github.com/rleibman/jorlan';

export function Hero() {
  return (
    <section className="relative overflow-hidden border-b border-border/50">
      <div className="mx-auto max-w-7xl px-6 py-24 sm:py-32 lg:px-8">
        <div className="grid gap-12 lg:grid-cols-2 lg:gap-16 items-center">
          <div className="space-y-8">
            <div className="space-y-4">
              <h1 className="text-4xl sm:text-5xl lg:text-6xl tracking-tight">
                Your AI Assistant, Under Your Control
              </h1>
              <p className="text-lg text-muted-foreground max-w-2xl">
                An open-source, self-hosted assistant platform with memory, permissions, scheduling,
                and integrations for everyday life, home automation, communication, and software workflows.
              </p>
            </div>

            <div className="flex flex-wrap gap-4">
              <a
                href={GITHUB_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-2 px-6 py-3 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 transition-colors"
              >
                <Github className="size-5" />
                View on GitHub
              </a>
              <a
                href={`${GITHUB_URL}#readme`}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-2 px-6 py-3 bg-secondary text-secondary-foreground rounded-lg hover:bg-secondary/90 transition-colors"
              >
                <BookOpen className="size-5" />
                Read the Docs
              </a>
            </div>

            <div className="flex items-center gap-3 text-sm text-muted-foreground">
              <a
                href={`${GITHUB_URL}/releases/latest`}
                target="_blank"
                rel="noopener noreferrer"
                className="hover:text-foreground transition-colors underline underline-offset-2"
              >
                Latest release
              </a>
              <span>·</span>
              <a
                href={`${GITHUB_URL}/issues`}
                target="_blank"
                rel="noopener noreferrer"
                className="hover:text-foreground transition-colors underline underline-offset-2"
              >
                Issues
              </a>
              <span>·</span>
              <a
                href={`${GITHUB_URL}/blob/main/INSTALL.md`}
                target="_blank"
                rel="noopener noreferrer"
                className="hover:text-foreground transition-colors underline underline-offset-2"
              >
                Installation guide
              </a>
            </div>
          </div>

          <div className="bg-card border border-border rounded-xl overflow-hidden shadow-2xl p-8 space-y-4">
            <div className="flex items-center gap-3 mb-6">
              <Github className="size-6 text-primary" />
              <span className="font-mono text-sm text-muted-foreground">rleibman/jorlan</span>
            </div>
            <div className="space-y-3 font-mono text-sm">
              <div className="flex items-start gap-2">
                <span className="text-primary select-none">$</span>
                <span className="text-foreground">sudo dpkg -i jorlan-server_*.deb</span>
              </div>
              <div className="flex items-start gap-2">
                <span className="text-primary select-none">$</span>
                <span className="text-foreground">sudo systemctl start jorlan-server</span>
              </div>
              <div className="flex items-start gap-2 pt-1">
                <span className="text-green-400 select-none">✓</span>
                <span className="text-muted-foreground">Running at http://localhost:8080</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
