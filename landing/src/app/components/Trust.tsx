import { MessageSquare, Database, FileSearch, ShieldCheck, Cog, CheckCircle2 } from 'lucide-react';

const steps = [
  { icon: MessageSquare, label: 'User Request', color: 'blue' },
  { icon: Database, label: 'Memory Lookup', color: 'purple' },
  { icon: FileSearch, label: 'Planning', color: 'cyan' },
  { icon: ShieldCheck, label: 'Permission Check', color: 'yellow' },
  { icon: Cog, label: 'Skill Execution', color: 'green' },
  { icon: CheckCircle2, label: 'Result', color: 'emerald' },
];

const colorMap: Record<string, string> = {
  blue: 'bg-blue-500/20 text-blue-400 border-blue-500/30',
  purple: 'bg-purple-500/20 text-purple-400 border-purple-500/30',
  cyan: 'bg-cyan-500/20 text-cyan-400 border-cyan-500/30',
  yellow: 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30',
  green: 'bg-green-500/20 text-green-400 border-green-500/30',
  emerald: 'bg-emerald-500/20 text-emerald-400 border-emerald-500/30',
};

export function Trust() {
  return (
    <section className="py-24 border-b border-border/50">
      <div className="mx-auto max-w-7xl px-6 lg:px-8">
        <div className="space-y-16">
          <div className="text-center space-y-4">
            <h2 className="text-3xl sm:text-4xl">Know What Your Assistant Is Doing</h2>
            <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
              Every action can be traced, audited, and reviewed. Full transparency from request to result.
            </p>
          </div>

          <div className="relative">
            <div className="grid gap-6 md:grid-cols-6">
              {steps.map((step, index) => (
                <div key={step.label} className="relative">
                  <div className="flex flex-col items-center text-center space-y-3">
                    <div className={`inline-flex p-4 rounded-xl border-2 ${colorMap[step.color]}`}>
                      <step.icon className="size-6" />
                    </div>
                    <div className="space-y-1">
                      <div className="font-medium">{step.label}</div>
                    </div>
                  </div>
                  {index < steps.length - 1 && (
                    <div className="hidden md:block absolute top-8 left-full w-full h-0.5 bg-border"
                         style={{ width: 'calc(100% - 4rem)', marginLeft: '2rem' }} />
                  )}
                </div>
              ))}
            </div>
          </div>

          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-5 max-w-5xl mx-auto">
            <div className="p-6 bg-card border border-border rounded-lg text-center space-y-2">
              <div className="font-medium">Human Approvals</div>
              <p className="text-sm text-muted-foreground">Sensitive actions require explicit user consent</p>
            </div>
            <div className="p-6 bg-card border border-border rounded-lg text-center space-y-2">
              <div className="font-medium">Permission Controls</div>
              <p className="text-sm text-muted-foreground">Granular control over what the assistant can do</p>
            </div>
            <div className="p-6 bg-card border border-border rounded-lg text-center space-y-2">
              <div className="font-medium">Activity History</div>
              <p className="text-sm text-muted-foreground">Complete log of all assistant actions</p>
            </div>
            <div className="p-6 bg-card border border-border rounded-lg text-center space-y-2">
              <div className="font-medium">Execution Logs</div>
              <p className="text-sm text-muted-foreground">Detailed traces of every operation</p>
            </div>
            <div className="p-6 bg-card border border-border rounded-lg text-center space-y-2">
              <div className="font-medium">Transparency</div>
              <p className="text-sm text-muted-foreground">No hidden behaviors or opaque decisions</p>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
