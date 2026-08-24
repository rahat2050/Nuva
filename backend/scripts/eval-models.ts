/**
 * MODEL EVALUATION HARNESS (roadmap follow-up: "compare gpt-oss-20b vs
 * gpt-oss-120b on a Bangla/Banglish command set").
 *
 *   npm run eval                          # compare GROQ_MODEL vs GROQ_FALLBACK_MODEL
 *   npm run eval -- --models openai/gpt-oss-20b
 *   GROQ_API_KEY=... npm run eval
 *
 * Each case in scripts/eval-dataset.json is sent through the REAL production
 * pipeline (normalize → Groq → strict validation → risk). A case scores:
 *
 *   intent ✓  result.intent === expect.intent
 *   risk   ✓  result.risk === expect.risk
 *   action ✓  every field of expect.action is present with the same value in
 *              result.action (subset match — server-side enrichment such as the
 *              package hint is allowed to add fields)
 *
 * The dataset itself is kept honest by tests/eval.test.ts, which refuses to let
 * a case into the suite whose expected action fails the strict registry schema.
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { getEnv, groqConfigured, type NuvaEnv } from '../lib/env';
import { createLogger, type Logger } from '../lib/logger';
import { interpretCommand } from '../lib/pipeline';

const here = dirname(fileURLToPath(import.meta.url));

interface EvalCase {
  id: string;
  text: string;
  language: 'auto' | 'bn' | 'en' | 'banglish';
  expect: {
    intent: string;
    risk: string;
    action?: Record<string, unknown>;
  };
}

interface CaseResult {
  id: string;
  intentOk: boolean;
  riskOk: boolean;
  actionOk: boolean | null;
  latencyMs: number | null;
  error: string | null;
  source: string | null;
}

interface ModelReport {
  model: string;
  cases: CaseResult[];
  errors: number;
  intentAccuracy: number;
  riskAccuracy: number;
  actionAccuracy: number | null;
  avgLatencyMs: number | null;
  maxLatencyMs: number | null;
  fallbackUses: number;
}

function loadDataset(): EvalCase[] {
  const raw = readFileSync(resolve(here, 'eval-dataset.json'), 'utf8');
  const dataset = JSON.parse(raw) as EvalCase[];
  if (!Array.isArray(dataset) || dataset.length === 0) {
    throw new Error('eval-dataset.json is empty or malformed');
  }
  for (const item of dataset) {
    if (typeof item.id !== 'string' || typeof item.text !== 'string' || !item.expect) {
      throw new Error(`malformed eval case: ${JSON.stringify(item).slice(0, 120)}`);
    }
  }
  return dataset;
}

function actionSubsetMatches(expected: Record<string, unknown> | undefined, actual: unknown): boolean | null {
  if (expected === undefined) return null;
  if (actual === null || typeof actual !== 'object') return false;
  const candidate = actual as Record<string, unknown>;
  for (const [key, value] of Object.entries(expected)) {
    const actualValue = candidate[key];
    if (typeof value === 'object' && value !== null) {
      if (actionSubsetMatches(value as Record<string, unknown>, actualValue) === false) return false;
    } else if (actualValue !== value) {
      return false;
    }
  }
  return true;
}

function pct(numerator: number, denominator: number): string {
  if (denominator === 0) return '—';
  return `${((100 * numerator) / denominator).toFixed(1)}%`;
}

async function runModel(model: string, dataset: EvalCase[], baseEnv: NuvaEnv, logger: Logger): Promise<ModelReport> {
  const env: NuvaEnv = { ...baseEnv, groqModel: model };
  const results: CaseResult[] = [];

  for (const testCase of dataset) {
    try {
      const response = await interpretCommand({
        request: { text: testCase.text, language: testCase.language },
        identity: { userId: null, deviceId: 'eval-harness', ip: null },
        logger,
        requestId: `eval-${testCase.id}`,
        env,
      });
      results.push({
        id: testCase.id,
        intentOk: response.result.intent === testCase.expect.intent,
        riskOk: response.result.risk === testCase.expect.risk,
        actionOk: actionSubsetMatches(testCase.expect.action, response.result.action),
        latencyMs: response.meta.latency_ms,
        error: null,
        source: response.meta.source,
      });
    } catch (err) {
      results.push({
        id: testCase.id,
        intentOk: false,
        riskOk: false,
        actionOk: testCase.expect.action === undefined ? null : false,
        latencyMs: null,
        error: err instanceof Error ? err.message : 'unknown error',
        source: null,
      });
    }
  }

  const answered = results.filter((r) => r.error === null);
  const actionScored = results.filter((r) => r.actionOk !== null);
  const latencies = answered.map((r) => r.latencyMs ?? 0).filter((ms) => ms > 0);

  return {
    model,
    cases: results,
    errors: results.filter((r) => r.error !== null).length,
    intentAccuracy: results.filter((r) => r.intentOk).length / results.length,
    riskAccuracy: results.filter((r) => r.riskOk).length / results.length,
    actionAccuracy: actionScored.length > 0 ? actionScored.filter((r) => r.actionOk === true).length / actionScored.length : null,
    avgLatencyMs: latencies.length > 0 ? Math.round(latencies.reduce((a, b) => a + b, 0) / latencies.length) : null,
    maxLatencyMs: latencies.length > 0 ? Math.max(...latencies) : null,
    fallbackUses: answered.filter((r) => r.source === 'fallback').length,
  };
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  const modelsFlag = args.indexOf('--models');
  const baseEnv = getEnv();

  if (!groqConfigured(baseEnv)) {
    console.error('GROQ_API_KEY is not set — the evaluation harness compares real model calls.');
    console.error('Usage: GROQ_API_KEY=gsk_... npm run eval [-- --models m1,m2]');
    process.exitCode = 1;
    return;
  }

  const models =
    modelsFlag !== -1 && typeof args[modelsFlag + 1] === 'string'
      ? (args[modelsFlag + 1] as string).split(',').map((m) => m.trim()).filter((m) => m.length > 0)
      : [baseEnv.groqModel, baseEnv.groqFallbackModel];

  const dataset = loadDataset();
  const logger = createLogger({ endpoint: 'eval' });
  logger.info('model evaluation starting', { models, cases: dataset.length });

  const reports: ModelReport[] = [];
  for (const model of models) {
    console.log(`\n▶ Running ${dataset.length} cases through ${model} …`);
    const report = await runModel(model, dataset, baseEnv, logger);
    reports.push(report);

    for (const result of report.cases) {
      const marks = [
        result.intentOk ? 'intent ✓' : 'intent ✗',
        result.riskOk ? 'risk ✓' : 'risk ✗',
        result.actionOk === null ? 'action –' : result.actionOk ? 'action ✓' : 'action ✗',
      ].join('  ');
      const tail = result.error !== null ? `  ERROR: ${result.error}` : '';
      console.log(`  ${result.id.padEnd(28)} ${marks}  ${result.latencyMs ?? '–'}ms${tail}`);
    }
  }

  console.log('\n## Summary\n');
  console.log('| Model | Cases | Errors | Intent | Risk | Action | Avg latency | Max latency | Fallback uses |');
  console.log('| ----- | ----- | ------ | ------ | ---- | ------ | ----------- | ----------- | ------------- |');
  for (const report of reports) {
    console.log(
      [
        `| ${report.model} `,
        ` ${report.cases.length} `,
        ` ${report.errors} `,
        ` ${pct(report.cases.filter((r) => r.intentOk).length, report.cases.length)} `,
        ` ${pct(report.cases.filter((r) => r.riskOk).length, report.cases.length)} `,
        ` ${report.actionAccuracy === null ? '—' : pct(report.cases.filter((r) => r.actionOk === true).length, report.cases.filter((r) => r.actionOk !== null).length)} `,
        ` ${report.avgLatencyMs ?? '—'}ms `,
        ` ${report.maxLatencyMs ?? '—'}ms `,
        ` ${report.fallbackUses} |`,
      ].join('|'),
    );
  }

  const best = reports.reduce((a, b) => (b.intentAccuracy > a.intentAccuracy ? b : a), reports[0] as ModelReport);
  console.log(`\nRecommendation on this dataset: ${best.model} (highest intent accuracy).`);
  console.log('Pin it via GROQ_MODEL in the Vercel project env; ?deep=1 warns if it is ever decommissioned.');
}

void main();
