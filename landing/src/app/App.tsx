import { Hero } from './components/Hero';
import { Features } from './components/Features';
import { Skills } from './components/Skills';
import { UseCases } from './components/UseCases';
import { Screenshots } from './components/Screenshots';
import { Trust } from './components/Trust';
import { Architecture } from './components/Architecture';
import { Developer } from './components/Developer';
import { Comparison } from './components/Comparison';
import { OpenSource } from './components/OpenSource';
import { Footer } from './components/Footer';

export default function App() {
  return (
    <div className="dark min-h-screen bg-background text-foreground">
      <Hero />
      <Screenshots />
      <Features />
      <Skills />
      <UseCases />
      <Trust />
      <Architecture />
      <Developer />
      <Comparison />
      <OpenSource />
      <Footer />
    </div>
  );
}