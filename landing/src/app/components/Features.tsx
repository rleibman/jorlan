import { Shield, Brain, Calendar, Users, Puzzle, Lock } from 'lucide-react';

const features = [
  {
    icon: Lock,
    title: 'Private and Self-Hosted',
    description: 'Run Jorlan on your own infrastructure and control your own data.',
  },
  {
    icon: Shield,
    title: 'Permissioned by Design',
    description: 'Sensitive actions require approval. Jorlan follows a deny-by-default security model.',
  },
  {
    icon: Brain,
    title: 'Persistent Memory',
    description: 'Remember preferences, routines, projects, and household context.',
  },
  {
    icon: Calendar,
    title: 'Scheduling and Automation',
    description: 'Handle reminders, recurring chores, workflows, and long-running tasks.',
  },
  {
    icon: Users,
    title: 'Multi-User',
    description: 'Built for individuals, families, communities, and teams.',
  },
  {
    icon: Puzzle,
    title: 'Extensible',
    description: 'Add skills, connectors, and integrations using structured schemas.',
  },
];

export function Features() {
  return (
    <section className="py-24 border-b border-border/50">
      <div className="mx-auto max-w-7xl px-6 lg:px-8">
        <div className="grid gap-12 lg:grid-cols-3">
          {features.map((feature) => (
            <div key={feature.title} className="space-y-4">
              <div className="inline-flex p-3 bg-accent/50 rounded-lg border border-border">
                <feature.icon className="size-6 text-primary" />
              </div>
              <div className="space-y-2">
                <h3>{feature.title}</h3>
                <p className="text-muted-foreground">{feature.description}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
