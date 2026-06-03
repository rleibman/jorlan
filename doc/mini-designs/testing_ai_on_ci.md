# Comprehensive Guide: Testing AI in GitHub Actions CI with Ollama

Running **Ollama** directly within your **GitHub Actions CI/CD pipelines** allows you to automate code reviews, generate
unit tests, or evaluate LLM outputs locally—completely bypassing external LLM cloud API costs.

---

## 1. Quick Start Workflow & Caching Strategy

To prevent GitHub Actions from downloading large model files (1GB–5GB) on every single commit—which heavily drains your
build time and free monthly minutes—you must implement strict workflow caching.

The default model storage location for Ollama inside a GitHub Linux runner environment is `~/.ollama`.

### Production-Ready GitHub Actions Workflow

Create a file at `.github/workflows/ai-testing.yml` in your repository:

```yaml
name: Ollama AI Testing CI
on: [ push, pull_request ]

jobs:
  ai-test:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Code
        uses: actions/checkout@v4

      # 1. Cache the Ollama model directory to speed up future runs
      - name: Cache Ollama Models
        id: cache-ollama
        uses: actions/cache@v4
        with:
          path: ~/.ollama
          key: \({{ runner.os }}-ollama-\){{ matrix.model }}
          restore-keys: |
            \${{ runner.os }}-ollama-

      # 2. Automatically install Ollama on the runner
      - name: Setup Ollama
        uses: ai-action/setup-ollama@v2

      # 3. Only pull the model if it wasn't restored from the cache
      - name: Pull Model (If Cache Miss)
        if: steps.cache-ollama.outputs.cache-hit != 'true'
        run: |
          ollama pull llama3.2:1b

      # 4. Execute your automated AI tasks
      - name: Run AI Review / Prompt
        run: |
          ollama run llama3.2:1b "Analyze this repository structure"
```

---

## 2. Key Use Cases in CI

* **Automated Code Review:** Pass git diffs directly to your local model to catch bugs or styling issues before a pull
  request merges.
* **Automated Test Generation:** Use CLI utilities to dynamically generate missing unit tests for freshly modified files
  during the build phase.
* **CI Failure Healing:** Feed failing pipeline console logs into Ollama to automatically diagnose compilation errors or
  test suite failures.
* **Security Scanning:** Audit your package lockfiles or source code for vulnerabilities locally during the pipeline
  run.

---

## 3. Financial Costs & Resource Restrictions

Because Ollama runs entirely locally inside the CI container, you will never pay any AI API subscription or token
generation fees. However, standard platform restrictions apply depending on your GitHub account tier:

| Feature / Limit         | Public Repositories                      | Private Repositories                                 |
|:------------------------|:-----------------------------------------|:-----------------------------------------------------|
| **Financial Cost**      | **$0.00 (100% Free)**                    | **Free** up to your monthly minute cap               |
| **Monthly Minutes**     | **Unlimited** free minutes               | **2,000 free minutes/month** (shared across account) |
| **Cache Storage Limit** | **10 GB total** (Shared across the repo) | **10 GB total** (Shared across the repo)             |
| **Artifact Storage**    | **Free**                                 | **500 MB limit** (Do *not* save models as artifacts) |

---

## 4. Free Tier Hardware & Technical Performance Limits

Standard GitHub-hosted free runners come with tight infrastructural limits that dictate how you configure Ollama:

* **No GPU Acceleration:** Free runners run strictly on standard CPU architecture. Text generation is slow, averaging *
  *2 to 5 tokens per second**.
* **RAM & CPU Allocation:** Runners are limited to **2 vCPUs and 7 GB of RAM**.
* **Model Size Constraints:** Due to RAM limitations, large models like `llama3:8b` or `mixtral` will crash or run too
  slowly. You must limit your pipelines to **small parameters under 4B**, such as:
    * `llama3.2:1b` (Highly recommended for CI speed)
    * `gemma2:2b`
    * `phi3:mini`

### 💡 Pro-Tip for Bypassing CPU Slowness

If your workflows take too long, you can hook up your own GPU-enabled machine as a **GitHub Self-Hosted Runner**. Keep
in mind that GitHub charges a minor platform orchestration fee of **$0.002 per minute** to coordinate self-hosted
runners, even on free tiers and public repositories.
