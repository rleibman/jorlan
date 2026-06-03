import { Github, BookOpen, FileCode, Users } from 'lucide-react';

export function OpenSource() {
  return (
    <section className="py-24 border-b border-border/50">
      <div className="mx-auto max-w-7xl px-6 lg:px-8">
        <div className="space-y-12">
          <div className="text-center space-y-4">
            <h2 className="text-3xl sm:text-4xl">Built in the Open</h2>
            <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
              Jorlan is an open-source project focused on creating trustworthy, extensible AI assistants
              for real-world use. We welcome contributors interested in AI, automation, home assistants,
              developer tools, privacy, and self-hosted software.
            </p>
          </div>

          <div className="flex flex-wrap justify-center gap-4">
            <button className="inline-flex items-center gap-2 px-6 py-3 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 transition-colors">
              <Github className="size-5" />
              GitHub
            </button>
            <button className="inline-flex items-center gap-2 px-6 py-3 bg-secondary text-secondary-foreground rounded-lg hover:bg-secondary/90 transition-colors">
              <BookOpen className="size-5" />
              Documentation
            </button>
            <button className="inline-flex items-center gap-2 px-6 py-3 bg-secondary text-secondary-foreground rounded-lg hover:bg-secondary/90 transition-colors">
              <FileCode className="size-5" />
              Architecture
            </button>
            <button className="inline-flex items-center gap-2 px-6 py-3 bg-secondary text-secondary-foreground rounded-lg hover:bg-secondary/90 transition-colors">
              <Users className="size-5" />
              Community
            </button>
          </div>
        </div>
      </div>
    </section>
  );
}
