# ZIO Layers: When to Use Them and When Not To

In ZIO, a Layer (ZLayer) represents a recipe for constructing a component or a group of components. If your codebase
feels over-engineered, you are likely treating layers like traditional object-oriented dependency injection (DI)
frameworks, creating a new layer for every single class or interface.Here is the criteria to help you decide when to use
a layer and when to stop using them.

## When to Make Something a Layer

You should only wrap a component in a ZLayer if it meets at least one of these four criteria:

- It has a lifecycle: It needs scoped initialization or cleanup (e.g., closing a database connection pool, stopping a
  thread pool, deleting a temp file).
- It must be a singleton: The component must share mutable state, a cache, or a connection pool across your entire
  application.
- It needs environment-based swapping: You
  need to completely swap the implementation for testing or different environments (e.g., swapping a real S3Client with
  an InMemoryS3Client).
- It relies on a heavy external resource: It connects to a database, an external HTTP API, or a message broker.

## When NOT to Make a Layer

If your components do not meet the criteria above, avoid ZLayer. Instead, use standard
Scala and ZIO patterns:

- Pure logic functions: If a component just transforms data or performs business logic without state, use pure functions
  or standard Scala case classes.
- Transient objects: If you need a new instance every time, use a factory function or a standard ZIO effect.
- Static configuration data: Pass simple configuration models case-by-case via function arguments rather than injecting
  them as layers.
- Intermediary business services: If ServiceA just calls ServiceB and ServiceC to do business logic without its own
  lifecycle, it does not need to be a layer. Pass its dependencies directly in its constructor.

## Signs of "Layer Fatigue" and How to Fix It

If you feel your system has too many layers, look
for these anti-patterns:

- The 1:1 Interface-to-Implementation Trap: Do not create trait UserService and class UserServiceImpl just to wrap the
  implementation in a layer. If you only have one implementation, use a concrete case class and turn it into a layer
  only if it manages resources.
- Micro-Layers: Do not create a separate layer for every single database table or API endpoint. Group related database
  operations into a single Repository layer.
- Deeply Nested Graph Construction: If your main method spends 50 lines of code composing layers `(LayerA.live >>>
  LayerB.live ++ LayerC.live)`, group them into a single, cohesive vertical layer (e.g., `val apiDependencies =
  DB.live >>> ( UserRepo.live ++ OrderRepo.live) >>> ApiServices.live)`.

## Summary Rule of Thumb

Think of a ZLayer as a heavy-duty resource manager, not a standard object factory. If a component is stateless and has
no lifecycle, instantiate it normally with standard Scala constructor injection.
