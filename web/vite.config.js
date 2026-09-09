import { defineConfig } from "vite";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

// This config is driven by sbt (see `webDist` / `webDebugDist` in build.sbt), NOT the other way around.
//
// The usual community setup is @scala-js/vite-plugin-scalajs, which resolves `scalajs:main.js` by spawning
// `sbt print fastLinkJSOutput` as a child process. That inverts the dependency -- npm drives sbt -- and running
// it from inside our own dist task would mean sbt re-entering sbt, fighting over the server lock, for a path the
// calling task already knows. So sbt hands the two paths vite needs over in the environment.
function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set; run this build through sbt's web/webDist or web/webDebugDist.`);
  return value;
}

const scalaJSOutputDir = required("SCALAJS_OUTPUT_DIR");
const outDir = required("VITE_OUT_DIR");

// The Scala.js linker output imports npm packages by bare name (react, @mui/material, ...). Node resolution walks
// UP from the importing file to find node_modules -- and under sbt 2 that output lives at <repo>/target/out/sjs1/...,
// nowhere near web/node_modules, so every bare import fails to resolve.
//
// Rather than move the npm project to the repo root (the layout the Scala.js tutorial assumes, which only works
// because sbt 1 kept linker output under the project directory), re-resolve those imports as if they came from
// web/. Everything reachable from node_modules then resolves normally, because the importer is inside web/ again.
const resolveScalaJSImportsFromWeb = {
  name: "scalajs-bare-imports",
  enforce: "pre",
  async resolveId(source, importer, options) {
    if (!importer || !importer.startsWith(scalaJSOutputDir)) return null;
    if (source.startsWith(".") || path.isAbsolute(source)) return null;
    const resolved = await this.resolve(source, path.join(here, "main.js"), { ...options, skipSelf: true });
    return resolved ?? null;
  },
};

export default defineConfig(({ mode }) => ({
  root: here,
  // Static assets are copied by webDistImpl in build.sbt, which has to do it anyway to lay them alongside
  // dist/imagerepo. Letting vite copy them too would only duplicate the work.
  publicDir: false,
  build: {
    outDir,
    // outDir is a staging directory under target/ that only this build writes, so emptying it is safe and keeps
    // hashed bundles from piling up -- which is exactly what the esbuild output did, ten bundles and 312MB of
    // them. dist/ and debugDist/ are a separate copy step in build.sbt, because dist/imagerepo holds recipe
    // images that are not a build product and must survive.
    emptyOutDir: true,
    minify: mode === "production",
    // Production stack traces are unreadable without this; forcing it on is what the old esbuild script patch was for.
    sourcemap: true,
    rollupOptions: {
      // React 18+ libraries (@mui/*, @emotion/*, ...) ship "use client" banners for bundlers that understand RSC.
      // Rollup does not, so it warns once per file -- ~250 lines per build here -- and then warns AGAIN because it
      // cannot map that warning back through the dependency's own sourcemap. Neither says anything about this app,
      // and the volume buries warnings that do. Both are dropped only for files inside node_modules; anything
      // originating in our own sources still comes through.
      onwarn(warning, warn) {
        const fromDependency = (warning.id ?? warning.loc?.file ?? "").includes("node_modules");
        if (fromDependency && warning.code === "MODULE_LEVEL_DIRECTIVE") return;
        if (fromDependency && warning.code === "SOURCEMAP_ERROR") return;
        warn(warning);
      },
    },
  },
  resolve: {
    alias: [
      { find: /^scalajs$/, replacement: path.resolve(scalaJSOutputDir, "main.js") },
    ],
  },
  plugins: [resolveScalaJSImportsFromWeb],
}));
