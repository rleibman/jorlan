import {
  UtensilsCrossed, Mail, Plane, Cake, Home, Music,
  Wrench, Activity, FolderKanban, Video, Code, MessageSquare
} from 'lucide-react';

const useCases = [
  {
    icon: UtensilsCrossed,
    title: 'Meal Planning',
    description: 'Generate weekly meal plans and shopping lists based on preferences and dietary needs.',
  },
  {
    icon: Mail,
    title: 'Email Inbox Management',
    description: 'Triage, draft, and organize emails with smart prioritization.',
  },
  {
    icon: Plane,
    title: 'Travel Planning',
    description: 'Research destinations, book accommodations, and manage itineraries.',
  },
  {
    icon: Cake,
    title: 'Birthday Reminders',
    description: 'Never miss a birthday with timely reminders and gift suggestions.',
  },
  {
    icon: Home,
    title: 'Smart Home',
    description: 'Control lights, thermostats, and devices with intelligent automation.',
  },
  {
    icon: Music,
    title: 'Music Collection Manager',
    description: 'Organize playlists, discover music, and control playback.',
  },
  {
    icon: Wrench,
    title: 'Home Maintenance Manager',
    description: 'Track maintenance tasks, repairs, and scheduled inspections.',
  },
  {
    icon: Activity,
    title: 'Weight & Exercise Tracking',
    description: 'Log workouts, track progress, and maintain health goals.',
  },
  {
    icon: FolderKanban,
    title: 'Project Manager',
    description: 'Organize tasks, track milestones, and coordinate team activities.',
  },
  {
    icon: Video,
    title: 'Meeting Assistant',
    description: 'Schedule meetings, send agendas, and capture action items.',
  },
  {
    icon: Code,
    title: 'Software Development Manager',
    description: 'Track issues, review PRs, and coordinate deployments.',
  },
  {
    icon: MessageSquare,
    title: 'Community Manager',
    description: 'Moderate discussions, send announcements, and engage members.',
  },
];

export function UseCases() {
  return (
    <section className="py-24 border-b border-border/50">
      <div className="mx-auto max-w-7xl px-6 lg:px-8">
        <div className="space-y-12">
          <div className="text-center space-y-4">
            <h2 className="text-3xl sm:text-4xl">One Assistant, Many Jobs</h2>
            <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
              Jorlan adapts to your life, whether you're managing a household, running a project, or coordinating a team.
            </p>
          </div>

          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {useCases.map((useCase) => (
              <div
                key={useCase.title}
                className="p-6 bg-card border border-border rounded-lg hover:border-primary/50 transition-colors group"
              >
                <div className="space-y-3">
                  <div className="inline-flex p-2.5 bg-accent/50 rounded-lg border border-border group-hover:border-primary/50 transition-colors">
                    <useCase.icon className="size-5 text-primary" />
                  </div>
                  <div className="space-y-1">
                    <h4>{useCase.title}</h4>
                    <p className="text-sm text-muted-foreground">{useCase.description}</p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
