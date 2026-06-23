import { Github, BookOpen, FileCode, Users, Star, GitFork, Bug } from 'lucide-react';

const GITHUB_URL = 'https://github.com/rleibman/jorlan';

export function OpenSource() {
  return (
    <section className="py-24 border-b border-border/50">
      <div className="mx-auto max-w-7xl px-6 lg:px-8">
        <div className="space-y-12">
          <div className="text-center space-y-4">
            <h2 className="text-3xl sm:text-4xl">Built in the Open</h2>
            <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
              Jorlan is an open-source project (Apache 2.0) focused on creating trustworthy, extensible
              AI assistants for real-world use. We welcome contributors interested in AI, automation, home
              assistants, developer tools, privacy, and self-hosted software.
            </p>
          </div>

          <div className="flex flex-wrap justify-center gap-4">
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
              href={`${GITHUB_URL}/blob/main/INSTALL.md`}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-2 px-6 py-3 bg-secondary text-secondary-foreground rounded-lg hover:bg-secondary/90 transition-colors"
            >
              <BookOpen className="size-5" />
              Installation Guide
            </a>
            <a
              href={`${GITHUB_URL}/blob/main/doc/SoftwareDesignDocument.md`}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-2 px-6 py-3 bg-secondary text-secondary-foreground rounded-lg hover:bg-secondary/90 transition-colors"
            >
              <FileCode className="size-5" />
              Architecture Docs
            </a>
            <a
              href={`${GITHUB_URL}/issues`}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-2 px-6 py-3 bg-secondary text-secondary-foreground rounded-lg hover:bg-secondary/90 transition-colors"
            >
              <Bug className="size-5" />
              Issues & Discussions
            </a>
          </div>

          <div className="grid sm:grid-cols-3 gap-6 max-w-2xl mx-auto">
            <a
              href={`${GITHUB_URL}/stargazers`}
              target="_blank"
              rel="noopener noreferrer"
              className="flex flex-col items-center gap-2 p-6 bg-card border border-border rounded-xl hover:border-primary/50 transition-colors"
            >
              <Star className="size-8 text-yellow-400" />
              <span className="text-sm font-medium">Star the repo</span>
              <span className="text-xs text-muted-foreground text-center">Show your support and stay updated</span>
            </a>
            <a
              href={`${GITHUB_URL}/fork`}
              target="_blank"
              rel="noopener noreferrer"
              className="flex flex-col items-center gap-2 p-6 bg-card border border-border rounded-xl hover:border-primary/50 transition-colors"
            >
              <GitFork className="size-8 text-blue-400" />
              <span className="text-sm font-medium">Fork & contribute</span>
              <span className="text-xs text-muted-foreground text-center">Add skills, fix bugs, improve docs</span>
            </a>
            <a
              href={`${GITHUB_URL}/issues/new`}
              target="_blank"
              rel="noopener noreferrer"
              className="flex flex-col items-center gap-2 p-6 bg-card border border-border rounded-xl hover:border-primary/50 transition-colors"
            >
              <Users className="size-8 text-green-400" />
              <span className="text-sm font-medium">Request a skill</span>
              <span className="text-xs text-muted-foreground text-center">Open an issue with your idea</span>
            </a>
          </div>
        </div>
      </div>
    </section>
  );
}
