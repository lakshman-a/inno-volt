# 🧪 Functional API Test Generation — Copilot QA Agent

> **An AI-powered assistant inside GitHub Copilot that generates, executes, and measures coverage of API functional test cases.**
> Select the agent, say "hi", follow the guided menu — production-ready test suites in minutes, not days.
>
> **Supported:** Cucumber + Serenity BDD · Karate DSL · JaCoCo Coverage · Allure Reports

---

## Table of Contents

1. [What Does It Do?](#1-what-does-it-do)
2. [Why Use This Instead of Manual Prompts?](#2-why-use-this-instead-of-manual-prompts)
3. [How to Set Up (5 Minutes)](#3-how-to-set-up-5-minutes)
4. [Files We Provide & Why](#4-files-we-provide--why)
5. [How to Use — Menu & Workflow](#5-how-to-use--menu--workflow)
6. [JaCoCo Code Coverage](#6-jacoco-code-coverage)
7. [Prerequisites](#7-prerequisites)
8. [Models, Tokens & Optimization](#8-models-tokens--optimization)
9. [Time Savings & Advantages](#9-time-savings--advantages)
10. [Who Should Use This?](#10-who-should-use-this)
11. [How to Expand & Contribute](#11-how-to-expand--contribute)

---

## 1. What Does It Do?

This is a **custom GitHub Copilot Agent** that acts as a Senior QA Automation Engineer. It sits inside your IDE and does what an experienced QA engineer would do — but in minutes.

| Capability | Description |
|---|---|
| 🔍 **Auto-Detects Your Project** | Scans workspace, detects framework (Cucumber or Karate), Java version, existing tests, dev project structure — including multi-module |
| 📋 **Plans Before Writing** | Shows test plan with exact scenario count (positive, negative, edge, business rules) — you approve before any code is generated |
| ✍️ **Generates Complete Suites** | Creates a standalone Maven test project with features, step defs, test data, schemas, config, runners, and README |
| 🚀 **Executes & Reports** | Runs the tests, shows pass/fail, generates shareable reports (Allure, Serenity, Karate HTML) |
| 📊 **JaCoCo Code Coverage** | Instruments your dev API locally, runs tests against it, generates code coverage report showing which lines your tests exercise |
| 🔄 **Enhances Existing Tests** | Analyzes current suite, finds gaps, identifies dead/weak tests, adds only what's missing — no duplicates |

---

## 2. Why Use This Instead of Manual Prompts?

| Manual Copilot Prompting | With QA Agent Mode |
|---|---|
| You need to know what to ask for | **Agent shows you a menu** — pick a number |
| You write the prompt from scratch every time | **Pre-built workflow** with structured input collection |
| Different developers get inconsistent results | **Same quality every time** — skills enforce standards |
| Easy to forget negative cases or edge cases | **Systematic coverage matrix** ensures nothing is missed |
| Tests often have hardcoded URLs and data | **Config-driven** — environment switching built in |
| No execution or reports after generation | **Always executes** and produces reports |
| No code coverage measurement | **JaCoCo integration** — actual line/branch coverage |
| Tests may be created inside dev project | **Always separate project** — CI/CD ready |

> **The key difference:** The agent provides a complete **workflow**, not just code generation. It's the difference between giving someone a fish and teaching them to fish — except the agent also cooks it and serves it on a plate.

---

## 3. How to Set Up (5 Minutes)

**Step 1 — Get the files**
Download the `.github/` folder from the platform team repository.

**Step 2 — Copy to your project**
Place the `.github/` folder at your workspace root (alongside your dev project).

**Step 3 — Open VS Code**
Ensure version 1.106+ with GitHub Copilot and Copilot Chat extensions installed.

**Step 4 — Enable settings (one-time)**

```
Settings → search "useCustomInstructions" → ✅ Enable
Settings → search "chat.agent.enabled"    → ✅ Enable
```

**Step 5 — Select the agent**
Open Copilot Chat → Click agent dropdown → Select **"Senior QA Automation Engineer"** → Set model to **Claude Sonnet 4.5**

**Step 6 — Type "hi"**
The agent initializes, scans your workspace, and shows you the menu. Done!

> ⚠️ **Troubleshooting:** Agent not appearing? Run `Ctrl+Shift+P → Developer: Reload Window`. Still missing? Check `Ctrl+Shift+P → Chat: Diagnostics` to verify files are loaded.

---

## 4. Files We Provide & Why

| File | Purpose | Loads When |
|---|---|---|
| `copilot-instructions.md` | Global rules: no hallucination, separate project, always execute | Every chat |
| `agents/senior-qa-automation.agent.md` | Agent persona, menu system, workflow phases, telemetry | When agent selected |
| `instructions/qa-testing.instructions.md` | Rules applied when editing test files (assertions, tags, data) | Editing test files |
| `skills/qa-cucumber-serenity/SKILL.md` | Cucumber + Serenity BDD project structure, POM, config patterns | On-demand |
| `skills/qa-karate-dsl/SKILL.md` | Karate DSL project structure, karate-config.js, match patterns | On-demand |
| `skills/qa-test-design/SKILL.md` | Coverage matrices, CRUD test patterns, gap analysis algorithm | On-demand |
| `skills/qa-test-execution/SKILL.md` | Execution commands, Allure/Serenity reports, parallel config | On-demand |
| `skills/qa-jacoco-coverage/SKILL.md` | JaCoCo Maven setup, instrumentation, dump, report generation | On-demand |
| `skills/qa-test-data-assertions/SKILL.md` | Reusable data patterns, field-level assertions, DB validation | On-demand |

> **Why skills load on-demand:** Skills only load when the task matches their description — not all at once. This keeps context efficient and avoids wasting tokens. You can also remove skills you don't need (e.g., delete the Cucumber folder if you only use Karate).

---

## 5. How to Use — Menu & Workflow

### Main Menu

Type **"hi"** or **"menu"** anytime to see this menu:

| # | Option | When to Use |
|---|---|---|
| **1** | Create New Test Suite | No tests exist — provide Swagger, code, or requirements |
| **2** | Enhance Existing Suite | Tests exist but need more negative/edge cases |
| **3** | Update for Changes | API changed, schema changed, new requirements |
| **4** | Single Endpoint Tests | Quick tests for one specific endpoint |
| **5** | Coverage Analysis | See what's missing without generating code |
| **6** | Fix Failing Tests | Debug and fix broken tests |
| **7** | Scaffold Framework | Start from scratch — skeleton + health check first, then add tests |
| **8** | JaCoCo Coverage | Measure which dev code your tests actually exercise |

### Typical Flow — Creating New Tests

1. Agent scans workspace → detects Karate + Spring Boot project
2. You select option **1** (Create New Test Suite)
3. Agent asks: "What inputs do you have?" — Swagger / Code / Jira / DB Schema / etc.
4. You say: "B and C — look at CustomerController + here's the Jira story"
5. Agent reads code, extracts endpoints, presents **test plan** (45 scenarios)
6. You approve → Agent creates separate test project at workspace root
7. Agent **executes** tests → shows pass/fail → generates Allure report
8. You type "jacoco" → Agent instruments dev API → runs tests → shows **coverage report**

### Input Quality Guide

| Inputs Provided | Test Quality |
|---|---|
| Swagger only | Basic endpoint coverage |
| Swagger + Requirements | Requirement-aligned tests |
| Swagger + Code + Requirements | Comprehensive with business logic |
| All above + DB Schema | Full coverage with database validation |
| All above + Existing Test Plan | Maximum precision and coverage |
| No inputs provided | Agent asks structured questions — still works! |

> **More inputs = more accurate test cases.** Without context, the agent creates placeholder assertions that intentionally fail with TODO messages, ensuring developers complete them.

---

## 6. JaCoCo Code Coverage

The agent can measure **actual code coverage** of your functional tests using JaCoCo — showing which lines, branches, and methods in your dev code are exercised by your test suite.

### How It Works

1. Agent adds JaCoCo Maven plugin to dev pom.xml (downloads through **corporate Artifactory** — no firewall issues)
2. Builds the dev project fresh (always — never skips this)
3. Starts the API with JaCoCo agent (`java -jar`, not `spring-boot:run`)
4. Runs your test suite against the instrumented API
5. Dumps coverage data and generates HTML report
6. Opens the report and presents a **gap analysis with recommendations**

### What You Get

- HTML report showing per-class coverage (instructions, lines, branches, methods)
- Coverage summary with overall percentages
- AI-driven gap analysis recommending specific new test scenarios
- Suggested next steps to improve coverage

### Session Management

- **New session** — fresh coverage report
- **Add to existing** — accumulate coverage across multiple test runs
- Choose which **tags or environment** to run, or default to full suite

> ⚠️ **Requirements:** The dev project must be in your workspace and runnable locally. If it can't start (missing DB, config, etc.), the agent falls back to an AI-estimated coverage analysis with a clear disclaimer.

---

## 7. Prerequisites

| Requirement | Details |
|---|---|
| VS Code | Version **1.106** or later |
| GitHub Copilot | Copilot + Copilot Chat extensions installed |
| Claude Model Access | Claude Sonnet 4.5 recommended (check org settings with admin) |
| Java | JDK 11, 17, or 21 (agent auto-detects version) |
| Maven | 3.8+ (for building and running tests) |
| Dev Project | Spring Boot / Java API (for test creation and JaCoCo) |

---

## 8. Models, Tokens & Optimization

### Recommended Model Usage

| Task | Model | Why |
|---|---|---|
| Test planning & design | **Claude Sonnet 4.5** (premium) | Best reasoning for complex coverage matrices |
| Scaffold & config generation | GPT-4o (free) | Straightforward code generation |
| Running tests & commands | GPT-4o Mini (free) | Just executing commands |
| Complex gap analysis | **Claude Opus 4.5** (premium) | Deep reasoning for large suites |
| JaCoCo execution steps | GPT-4o (free) | Following step-by-step commands |
| Fixing compilation errors | GPT-4o (free) | Simple code fixes |

> **Tip:** Switch models in the Copilot Chat model dropdown. Use Claude for the thinking-heavy steps, free models for execution. This stretches your premium requests significantly.

### Token Optimization Built In

- Skills load **on-demand** — only when matched to the task, not all at once
- Large suites **auto-split into phases** — 30+ scenarios generated in batches
- When enhancing existing suites — only the **delta** is generated, not a full rewrite
- Scaffold-first approach — verify the framework works before spending tokens on test cases

---

## 9. Time Savings & Advantages

### Benchmarks

| Task | Manual Effort | With Agent | Time Saved |
|---|---|---|---|
| Scaffold test framework | 2–4 hours | 5 minutes | **~95%** |
| Write 20 test scenarios | 1–2 days | 15–30 minutes | **~90%** |
| Set up JaCoCo for functional tests | Half day | 10 minutes | **~90%** |
| Gap analysis on existing suite | 2–4 hours | 5 minutes | **~95%** |
| Configure Allure reporting | 1–2 hours | Built in | **100%** |
| Environment config (dev/qa/uat) | 1 hour | Built in | **100%** |

### Beyond Time Savings

- **Consistency** — Every developer produces the same quality test suites regardless of QA experience
- **Coverage** — Systematic positive + negative + edge + business rule testing — nothing missed
- **Maintainability** — Clean layered code: config / auth / models / utils / features / data — anyone can browse and understand
- **CI/CD Ready** — Standalone Maven projects that ship directly to Jenkins without modification
- **Reporting** — Allure/Serenity reports shareable with stakeholders, managers, and audit
- **Code Coverage** — JaCoCo reports to prove test effectiveness with actual numbers
- **Enforcement** — Placeholder assertions `fail()` until developers complete them — tests never silently pass incomplete
- **Dead Test Detection** — Identifies empty scenarios, missing glue code, weak assertions, duplicates, and stale @ignore tests
- **Parallel Execution** — Suggests and configures parallel test runs for faster feedback
- **Telemetry** — Tracks agent activity (scenarios generated, coverage improvements) for platform team visibility

---

## 10. Who Should Use This?

| Role | How It Helps |
|---|---|
| **Developers** | Generate tests for your APIs as you build them. No QA dependency. Ship tested code faster. |
| **QA Engineers** | Accelerate test creation. Focus on test design and business logic — let the agent handle boilerplate and framework setup. |
| **Tech Leads** | Ensure consistent test quality across your squad. Track coverage improvements via telemetry data. |
| **New Team Members** | Onboard instantly. The agent guides you through everything — no need to learn the testing framework first. |

> **Try it now!** Open your project in VS Code → Copilot Chat → Select "Senior QA Automation Engineer" → Type "hi". The agent scans your project and guides you from there. Zero setup friction.

---

## 11. How to Expand & Contribute

### Current Support

| Testing Type | Framework | Status |
|---|---|---|
| API Functional Testing (REST) | Cucumber + Serenity BDD | ✅ Available |
| API Functional Testing (REST) | Karate DSL | ✅ Available |
| Code Coverage | JaCoCo | ✅ Available |
| Reporting | Allure / Serenity / Karate HTML | ✅ Available |
| UI Testing | Selenium / Playwright | 🟣 Planned |
| Database Testing | JDBC / JPA assertions | 🟣 Planned |
| Performance Testing | Gatling / JMeter | 🟣 Planned |

### Want to Add a New Framework?

1. Contact the **Platform QA Team**
2. We create a new `SKILL.md` file for your framework
3. Drop it into `.github/skills/` — the agent picks it up automatically
4. No changes needed to the agent file — skills are modular by design

> The architecture is designed to grow. Each testing type (API, UI, DB, Performance) is a skill. Adding new capabilities means adding new files — not rewriting existing ones.

### Telemetry & Platform Visibility

The agent silently tracks its activity in `.github/qa-agent-telemetry.json`:
- Scenarios generated, updated, removed
- Lines of code generated
- Coverage improvements (before → after)
- Test execution results (pass/fail/skip)
- Model used per session

This helps the Platform team measure value delivered and improve the agent. **No developer action needed** — it's invisible.

### Feedback

Using the agent and found something that could be better? Reach out to the **Platform QA Team**. Every improvement benefits all teams using the agent.

---

*QA Automation Agent v3.2 · Powered by GitHub Copilot + Claude · Maintained by Platform QA Team*
