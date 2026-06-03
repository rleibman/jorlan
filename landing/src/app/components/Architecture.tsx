import { Users, MessageSquare, Server, Puzzle, Database } from 'lucide-react';

const layers = [
  {
    icon: Users,
    title: 'Users',
    items: ['Web Interface', 'Mobile Apps', 'CLI', 'API Clients'],
  },
  {
    icon: MessageSquare,
    title: 'Communication Channels',
    items: ['Slack', 'Discord', 'Email', 'SMS'],
  },
  {
    icon: Server,
    title: 'Jorlan Runtime',
    items: ['Memory', 'Scheduler', 'Permissions', 'GraphQL API'],
  },
  {
    icon: Puzzle,
    title: 'Skills & Connectors',
    items: ['Home Assistant', 'GitHub', 'Calendar', 'Email'],
  },
  {
    icon: Database,
    title: 'External Systems',
    items: ['MariaDB', 'Ollama', 'APIs', 'Devices'],
  },
];

export function Architecture() {
  return (
    <section className="py-24 border-b border-border/50">
      <div className="mx-auto max-w-7xl px-6 lg:px-8">
        <div className="space-y-12">
          <div className="text-center space-y-4">
            <h2 className="text-3xl sm:text-4xl">Built Like Infrastructure, Not A Chatbot</h2>
            <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
              Jorlan is designed as a long-running system with proper state management, security, and extensibility.
            </p>
          </div>

          <div className="relative max-w-4xl mx-auto">
            <div className="space-y-6">
              {layers.map((layer, index) => (
                <div key={layer.title} className="relative">
                  <div className="bg-card border-2 border-border rounded-xl p-6">
                    <div className="flex items-start gap-6">
                      <div className="inline-flex p-3 bg-accent/50 rounded-lg border border-border flex-shrink-0">
                        <layer.icon className="size-6 text-primary" />
                      </div>
                      <div className="flex-1 space-y-3">
                        <h4>{layer.title}</h4>
                        <div className="flex flex-wrap gap-2">
                          {layer.items.map((item) => (
                            <div
                              key={item}
                              className="px-3 py-1.5 bg-accent/50 rounded-full border border-border text-sm"
                            >
                              {item}
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>
                  </div>
                  {index < layers.length - 1 && (
                    <div className="flex justify-center py-2">
                      <div className="w-px h-6 bg-border" />
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
