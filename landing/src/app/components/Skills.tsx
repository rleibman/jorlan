import {Github} from 'lucide-react';

const GITHUB_URL = 'https://github.com/rleibman/jorlan';

interface SkillCardProps {
    name: string;
    description: string;
    tier: 'built-in' | 'plugin';
    capabilities: string[];
    docsPath?: string;
}

function SkillCard({name, description, tier, capabilities, docsPath}: SkillCardProps) {
    return (
        <div
            className="bg-card border border-border rounded-xl p-6 space-y-3 hover:border-primary/40 transition-colors">
            <div className="flex items-start justify-between gap-2">
                <h3 className="font-semibold text-foreground">{name}</h3>
                <span
                    className={`text-xs px-2 py-0.5 rounded-full shrink-0 ${
                        tier === 'built-in'
                            ? 'bg-primary/15 text-primary'
                            : 'bg-muted text-muted-foreground'
                    }`}
                >
          {tier === 'built-in' ? 'Built-in' : 'Plugin'}
        </span>
            </div>
            <p className="text-sm text-muted-foreground">{description}</p>
            {capabilities.length > 0 && (
                <div className="flex flex-wrap gap-1">
                    {capabilities.map((cap) => (
                        <span key={cap}
                              className="text-xs font-mono bg-muted/50 px-1.5 py-0.5 rounded text-muted-foreground">
              {cap}
            </span>
                    ))}
                </div>
            )}
            {docsPath && (
                <a
                    href={`${GITHUB_URL}/blob/main/${docsPath}`}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
                >
                    <Github className="size-3"/>
                    Docs
                </a>
            )}
        </div>
    );
}

const BUILT_IN_SKILLS: SkillCardProps[] = [
    {
        name: 'Memory',
        description: 'Store and retrieve notes, facts, and reminders across sessions. Agents remember what matters.',
        tier: 'built-in',
        capabilities: ['memory.read', 'memory.write'],
        docsPath: 'doc/skills/memory.md',
    },
    {
        name: 'Scheduler',
        description: 'Create and manage cron-based recurring jobs. Agents can schedule tasks to run without human prompting.',
        tier: 'built-in',
        capabilities: ['scheduler.manage'],
        docsPath: 'doc/skills/scheduler.md',
    },
    {
        name: 'Shell',
        description: 'Execute allowlisted shell commands on the host. Sandboxed by an explicit per-agent command allowlist.',
        tier: 'built-in',
        capabilities: ['shell.read', 'shell.execute'],
        docsPath: 'doc/skills/shell.md',
    },
    {
        name: 'Workspace',
        description: 'Read and write files in a persistent per-agent workspace directory. Each agent gets its own sandboxed filesystem.',
        tier: 'built-in',
        capabilities: ['workspace.read', 'workspace.write', 'workspace.delete'],
        docsPath: 'doc/skills/workspace.md',
    },
    {
        name: 'Contacts',
        description: 'Look up Jorlan users by name and manage channel identity links (Telegram ID, email) to user accounts.',
        tier: 'built-in',
        capabilities: ['contacts.read', 'identity.manage'],
        docsPath: 'doc/skills/contacts.md',
    },
    {
        name: 'Notify',
        description: 'Send messages to users through their preferred channel. Routes to Telegram, email, or any active connector.',
        tier: 'built-in',
        capabilities: ['notify.send'],
        docsPath: 'doc/skills/notify.md',
    },
    {
        name: 'User Management',
        description: 'Admin-level CRUD for Jorlan user accounts. Requires elevated permissions and is gated for administrative agents only.',
        tier: 'built-in',
        capabilities: ['admin.user.list', 'user.create', 'user.update'],
        docsPath: 'doc/skills/user-management.md',
    },
];

const PLUGIN_SKILLS: SkillCardProps[] = [
    {
        name: 'Calculator',
        description: 'Safe arithmetic expression evaluation. No external API required — pure local computation.',
        tier: 'plugin',
        capabilities: [],
        docsPath: 'calculator/README.md',
    },
    {
        name: 'Unit Conversion',
        description: 'Convert between any units of measurement: length, weight, temperature, volume, speed, and more.',
        tier: 'plugin',
        capabilities: [],
        docsPath: 'unit-conversion/README.md',
    },
    {
        name: 'Time & Date',
        description: 'Get current time in any timezone, convert between zones, format dates. Configurable default timezone per agent.',
        tier: 'plugin',
        capabilities: ['time.read'],
        docsPath: 'time-skill/README.md',
    },
    {
        name: 'Weather',
        description: 'Current conditions and forecasts via OpenWeatherMap. Configurable default location per agent.',
        tier: 'plugin',
        capabilities: ['weather.read'],
        docsPath: 'weather/README.md',
    },
    {
        name: 'Market Data',
        description: 'Real-time and historical stock prices, fundamentals, and currency exchange rates via Alpha Vantage.',
        tier: 'plugin',
        capabilities: ['market.read'],
        docsPath: 'market-data/README.md',
    },
    {
        name: 'Web Search',
        description: 'AI-optimized web search via Tavily. Returns clean, agent-friendly results rather than raw HTML.',
        tier: 'plugin',
        capabilities: ['search.read'],
        docsPath: 'search/README.md',
    },
    {
        name: 'HTTP Fetch',
        description: 'Fetch content from the web with an explicit per-agent allowlist of permitted hosts.',
        tier: 'plugin',
        capabilities: ['http_fetch.call'],
        docsPath: 'http-fetch/README.md',
    },
    {
        name: 'Lyrion Music',
        description: 'Control Lyrion Music Server (formerly Squeezebox Server). Play, pause, skip, and browse your library.',
        tier: 'plugin',
        capabilities: ['lyrion.control'],
        docsPath: 'lyrion/README.md',
    },
    {
        name: 'Telegram',
        description: 'Send messages to Telegram users from agent workflows. Pairs with the Telegram connector for two-way chat.',
        tier: 'plugin',
        capabilities: ['telegram.send'],
        docsPath: 'telegram/README.md',
    },
    {
        name: 'Email (Gmail)',
        description: 'Read, search, and send email via Gmail using OAuth 2.0. Supports labels, threads, and attachments.',
        tier: 'plugin',
        capabilities: ['gmail.read', 'gmail.send'],
        docsPath: 'email/README.md',
    },
    {
        name: 'Google Calendar',
        description: 'Read and create calendar events across all your Google Calendars. Supports recurring events and attendees.',
        tier: 'plugin',
        capabilities: ['calendar.read', 'calendar.write'],
        docsPath: 'google-services/README.md',
    },
    {
        name: 'Google Contacts',
        description: 'Search and read your Google Contacts. Useful for agent workflows that resolve people by name.',
        tier: 'plugin',
        capabilities: ['contacts_google.read'],
        docsPath: 'google-services/README.md',
    },
    {
        name: 'Google Drive',
        description: 'List files, read documents, and search your Google Drive. Supports Docs, Sheets, and binary files.',
        tier: 'plugin',
        capabilities: ['drive.read'],
        docsPath: 'google-services/README.md',
    },
];

export function Skills() {
    return (
        <section className="py-24 border-b border-border/50">
            <div className="mx-auto max-w-7xl px-6 lg:px-8 space-y-16">
                <div className="text-center space-y-4">
                    <h2 className="text-3xl sm:text-4xl">Skills & Integrations</h2>
                    <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
                        20 skills available out of the box. Built-in skills are always available; plugins are
                        installed separately and enabled by your administrator. Every skill uses the same
                        deny-by-default capability model.
                    </p>
                </div>

                <div className="space-y-10">
                    <div className="space-y-4">
                        <h3 className="text-xl font-semibold">Built-in Skills</h3>
                        <p className="text-sm text-muted-foreground">Always present — no installation required. Enable
                            per agent by granting the required capability.</p>
                        <div className="grid sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
                            {BUILT_IN_SKILLS.map((skill) => (
                                <SkillCard key={skill.name} {...skill} />
                            ))}
                        </div>
                    </div>

                    <div className="space-y-4">
                        <h3 className="text-xl font-semibold">Plugin Skills</h3>
                        <p className="text-sm text-muted-foreground">Installed as separate .deb packages or from the
                            skill directory. Require API keys or service credentials.</p>
                        <div className="grid sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
                            {PLUGIN_SKILLS.map((skill) => (
                                <SkillCard key={skill.name} {...skill} />
                            ))}
                        </div>
                    </div>
                </div>

                <div className="text-center">
                    <a
                        href={`${GITHUB_URL}/blob/main/doc/SoftwareDesignDocument.md`}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="inline-flex items-center gap-2 text-sm text-primary hover:underline"
                    >
                        <Github className="size-4"/>
                        Build your own skill — see the Skill API docs
                    </a>
                </div>
            </div>
        </section>
    );
}
