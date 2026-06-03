export function Developer() {
  const techStack = [
    'Scala 3',
    'ZIO',
    'GraphQL',
    'Local LLM Support',
    'Typed Skills',
    'Structured Permissions',
    'Orchestrator Integration',
  ];

  return (
    <section className="py-24 border-b border-border/50">
      <div className="mx-auto max-w-7xl px-6 lg:px-8">
        <div className="space-y-12">
          <div className="text-center space-y-4">
            <h2 className="text-3xl sm:text-4xl">Built for Extension</h2>
            <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
              Create custom skills and connectors with strongly-typed schemas and built-in safety guarantees.
            </p>
          </div>

          <div className="max-w-3xl mx-auto space-y-6">
            <div className="space-y-4">
              <h3>Technology Stack</h3>
              <p className="text-muted-foreground">
                Jorlan is built with modern, type-safe technologies that prioritize correctness,
                composability, and developer experience.
              </p>
            </div>

            <div className="flex flex-wrap gap-3">
              {techStack.map((tech) => (
                <div
                  key={tech}
                  className="px-4 py-2 bg-accent/50 rounded-lg border border-border"
                >
                  {tech}
                </div>
              ))}
            </div>

            <div className="space-y-3 pt-6">
              <h4>Key Features</h4>
              <ul className="space-y-2 text-muted-foreground">
                <li className="flex items-start gap-2">
                  <div className="size-1.5 rounded-full bg-primary mt-2 flex-shrink-0" />
                  <span>Strongly-typed skill definitions with compile-time validation</span>
                </li>
                <li className="flex items-start gap-2">
                  <div className="size-1.5 rounded-full bg-primary mt-2 flex-shrink-0" />
                  <span>GraphQL API for programmatic access to all assistant functions</span>
                </li>
                <li className="flex items-start gap-2">
                  <div className="size-1.5 rounded-full bg-primary mt-2 flex-shrink-0" />
                  <span>Support for local LLMs via Ollama for complete privacy</span>
                </li>
                <li className="flex items-start gap-2">
                  <div className="size-1.5 rounded-full bg-primary mt-2 flex-shrink-0" />
                  <span>Orchestrator integration for complex multi-step workflows</span>
                </li>
              </ul>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
