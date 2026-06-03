import { Github, BookOpen } from 'lucide-react';

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
              <button className="inline-flex items-center gap-2 px-6 py-3 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 transition-colors">
                <Github className="size-5" />
                View on GitHub
              </button>
              <button className="inline-flex items-center gap-2 px-6 py-3 bg-secondary text-secondary-foreground rounded-lg hover:bg-secondary/90 transition-colors">
                <BookOpen className="size-5" />
                Read the Documentation
              </button>
            </div>
          </div>

          <div className="bg-card border border-border rounded-xl overflow-hidden shadow-2xl aspect-video flex items-center justify-center">
            <div className="text-center space-y-4 p-12">
              <div className="size-24 mx-auto rounded-xl bg-accent/50 border border-border flex items-center justify-center">
                <Github className="size-12 text-muted-foreground/50" />
              </div>
              <p className="text-muted-foreground">Open source AI assistant platform</p>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
