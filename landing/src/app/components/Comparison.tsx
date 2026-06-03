import { Check, X } from 'lucide-react';

const features = [
  'Self Hosted',
  'Multi User',
  'Permissions',
  'Traceability',
  'Scheduling',
  'Home Automation',
  'Structured Skills',
  'Local Models',
  'Long-Running Workflows',
  'Orchestrator Integration',
];

const systems = [
  {
    name: 'Chat Assistants',
    values: [false, false, false, false, false, false, false, false, false, false],
  },
  {
    name: 'Agent Systems',
    values: [false, false, false, true, false, false, true, false, true, false],
  },
  {
    name: 'Jorlan',
    values: [true, true, true, true, true, true, true, true, true, true],
    highlight: true,
  },
];

export function Comparison() {
  return (
    <section className="py-24 border-b border-border/50">
      <div className="mx-auto max-w-7xl px-6 lg:px-8">
        <div className="space-y-12">
          <div className="text-center space-y-4">
            <h2 className="text-3xl sm:text-4xl">A Different Kind of Agent Platform</h2>
            <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
              Jorlan combines the capabilities of modern AI agents with the reliability and control of self-hosted infrastructure.
            </p>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full border-collapse">
              <thead>
                <tr className="border-b-2 border-border">
                  <th className="text-left p-4 font-medium">Feature</th>
                  {systems.map((system) => (
                    <th
                      key={system.name}
                      className={`text-center p-4 font-medium ${system.highlight ? 'bg-accent/30' : ''}`}
                    >
                      {system.name}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {features.map((feature, index) => (
                  <tr key={feature} className="border-b border-border hover:bg-accent/20">
                    <td className="p-4 text-muted-foreground">{feature}</td>
                    {systems.map((system) => (
                      <td
                        key={`${system.name}-${index}`}
                        className={`text-center p-4 ${system.highlight ? 'bg-accent/30' : ''}`}
                      >
                        {system.values[index] ? (
                          <div className="inline-flex items-center justify-center size-6 rounded-full bg-green-500/20">
                            <Check className="size-4 text-green-400" />
                          </div>
                        ) : (
                          <div className="inline-flex items-center justify-center size-6 rounded-full bg-red-500/20">
                            <X className="size-4 text-red-400" />
                          </div>
                        )}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <p className="text-sm text-muted-foreground text-center max-w-3xl mx-auto">
            This comparison highlights architectural differences. Each approach serves different needs.
            Jorlan is designed for users who want complete control, multi-user support, and infrastructure-grade reliability.
          </p>
        </div>
      </div>
    </section>
  );
}
