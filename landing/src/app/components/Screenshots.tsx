import { CheckCircle2, XCircle, PlayCircle, Home as HomeIcon, BookOpen, Code } from 'lucide-react';

const screenshots = [
  {
    id: 1,
    title: 'Assistant Dashboard',
    description: 'Monitor active tasks, upcoming reminders, and connected users',
    icon: HomeIcon,
  },
  {
    id: 2,
    title: 'Permission Approval',
    description: 'Review and approve sensitive actions before execution',
    icon: CheckCircle2,
  },
  {
    id: 3,
    title: 'Execution Trace',
    description: 'Step-by-step view of how requests are completed',
    icon: PlayCircle,
  },
  {
    id: 4,
    title: 'Smart Home Dashboard',
    description: 'Control devices, monitor sensors, and manage automations',
    icon: HomeIcon,
  },
  {
    id: 5,
    title: 'Use Case Library',
    description: 'Browse and activate pre-built workflows for common tasks',
    icon: BookOpen,
  },
  {
    id: 6,
    title: 'Developer View',
    description: 'Manage skills, connectors, APIs, and execution logs',
    icon: Code,
  },
];

function ScreenshotCard({ screenshot }: { screenshot: typeof screenshots[0] }) {
  return (
    <div className="bg-card border border-border rounded-xl overflow-hidden group hover:border-primary/50 transition-colors">
      <div className="aspect-video bg-accent/30 border-b border-border flex items-center justify-center p-12">
        <screenshot.icon className="size-16 text-muted-foreground/30" />
      </div>
      <div className="p-6 space-y-2">
        <h4>{screenshot.title}</h4>
        <p className="text-sm text-muted-foreground">{screenshot.description}</p>
      </div>
    </div>
  );
}

export function Screenshots() {
  return (
    <section className="py-24 border-b border-border/50">
      <div className="mx-auto max-w-7xl px-6 lg:px-8">
        <div className="space-y-12">
          <div className="text-center space-y-4">
            <h2 className="text-3xl sm:text-4xl">See What Jorlan Can Do</h2>
            <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
              A complete control center for your AI assistant, not just a chat window.
            </p>
          </div>

          <div className="grid gap-8 sm:grid-cols-2 lg:grid-cols-3">
            {screenshots.map((screenshot) => (
              <ScreenshotCard key={screenshot.id} screenshot={screenshot} />
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
