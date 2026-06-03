export function Footer() {
  return (
    <footer className="py-12">
      <div className="mx-auto max-w-7xl px-6 lg:px-8">
        <div className="flex flex-col items-center gap-6">
          <div className="text-2xl tracking-tight">Jorlan</div>
          <p className="text-sm text-muted-foreground text-center max-w-2xl">
            Open-source AI assistance with memory, permissions, scheduling, and control.
          </p>
          <div className="text-xs text-muted-foreground">
            jorlan-ai.com
          </div>
        </div>
      </div>
    </footer>
  );
}
