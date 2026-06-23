import { Github } from 'lucide-react';

const GITHUB_URL = 'https://github.com/rleibman/jorlan';

export function Footer() {
  return (
    <footer className="py-12 border-t border-border/50">
      <div className="mx-auto max-w-7xl px-6 lg:px-8">
        <div className="flex flex-col items-center gap-6">
          <a
            href={GITHUB_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-3 hover:opacity-80 transition-opacity"
          >
            <Github className="size-6" />
            <span className="text-2xl tracking-tight">Jorlan</span>
          </a>
          <p className="text-sm text-muted-foreground text-center max-w-2xl">
            Open-source AI assistance with memory, permissions, scheduling, and control.
            Licensed under Apache 2.0.
          </p>
          <div className="flex items-center gap-6 text-xs text-muted-foreground">
            <a
              href={GITHUB_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="hover:text-foreground transition-colors"
            >
              GitHub
            </a>
            <a
              href={`${GITHUB_URL}/blob/main/INSTALL.md`}
              target="_blank"
              rel="noopener noreferrer"
              className="hover:text-foreground transition-colors"
            >
              Install
            </a>
            <a
              href={`${GITHUB_URL}/blob/main/doc/SoftwareDesignDocument.md`}
              target="_blank"
              rel="noopener noreferrer"
              className="hover:text-foreground transition-colors"
            >
              Architecture
            </a>
            <a
              href={`${GITHUB_URL}/issues`}
              target="_blank"
              rel="noopener noreferrer"
              className="hover:text-foreground transition-colors"
            >
              Issues
            </a>
            <a
              href={`${GITHUB_URL}/blob/main/LICENSE`}
              target="_blank"
              rel="noopener noreferrer"
              className="hover:text-foreground transition-colors"
            >
              Apache 2.0
            </a>
          </div>
        </div>
      </div>
    </footer>
  );
}
