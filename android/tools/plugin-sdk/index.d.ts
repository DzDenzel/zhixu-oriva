/** Contract v2. The JNI boundary accepts and returns JSON strings only. */
export type JsonValue = null | boolean | number | string | JsonValue[] | JsonObject;
export interface JsonObject { [key: string]: JsonValue; }
export type ReportPeriod = 'day' | 'week' | 'month';
export type RuntimeCapability = 'OVERLAY' | 'HOME' | 'HAPTIC' | 'FEEDBACK' | 'QUERY_TODAY' | 'QUERY_PLAN' | 'QUERY_REPORT';
export type SystemCapability = 'USAGE_STATS' | 'ACCESSIBILITY' | 'DND' | 'NOTIFICATIONS' | 'LOCATION';
export type Capability = RuntimeCapability | SystemCapability;
export type MessageRequest =
  | { op: 'query.today'; args: { date?: string } }
  | { op: 'query.plan'; args: { id: number } }
  | { op: 'query.report'; args: { period: ReportPeriod } }
  | { op: 'overlay'; args: { text: string; displayMillis?: number } }
  | { op: 'home' | 'haptic'; args: Record<string, never> }
  | { op: 'feedback'; args: { code: string } };
export type MessageResponse = { ok: true; data: JsonValue } | { ok: false; error: string };
export interface Host { postMessage(requestJSON: string): string; }
export interface TodayPlan { id: number; name: string; enabled: boolean; inWindow: boolean; remainingMinutes: number; }
/** Shares the host AssistantSnapshot projection; no SDK aggregation or network access. */
export interface TodaySnapshot {
  today: string; arrangements: string[]; plans: TodayPlan[];
  continuousDays?: number | null; todayUnlocks?: number | null;
}
export interface HostClient {
  request(request: MessageRequest): JsonValue;
  overlay(text: string, displayMillis?: number): JsonValue;
  home(): JsonValue; haptic(): JsonValue; feedback(code: string): JsonValue;
  query: {
    today(date?: string): TodaySnapshot | null;
    plan(id: number): JsonObject | null;
    report(period: ReportPeriod): JsonObject | null;
  };
}
/** Includes host refusals, malformed responses and invalid client arguments. */
export class HostError extends Error { constructor(message: string); }
export function createHost(host: Host): HostClient;
interface ContributionBase { id: string; shapeVersion?: 1; }
export interface StatusLine extends ContributionBase {
  kind: 'StatusLine'; slot: 'plan.detail.protection'; text: string; tone?: 'neutral' | 'active' | 'warn';
  requiresCapability?: SystemCapability; unavailableText?: string;
}
export interface ActionRow extends ContributionBase {
  kind: 'ActionRow'; slot: 'plan.detail.protection'; label: string; actionId: string; input?: 'NONE' | 'APPLICATION_SINGLE';
}
export interface CapabilityRequest extends ContributionBase {
  kind: 'CapabilityRequest'; slot: 'plan.detail.protection'; reason: string; capabilityId: SystemCapability; actionId: string;
}
interface FieldBase { id: string; label: string; onEvent: string; }
export type FormField = (FieldBase & { kind: 'switch'; value: boolean }) |
  (FieldBase & { kind: 'text'; value: string }) |
  (FieldBase & { kind: 'choice'; value: string; options: { value: string; label: string }[] });
export interface FormSection extends ContributionBase {
  kind: 'FormSection'; slot: 'plan.edit.configuration'; title: string; fields: FormField[];
}
export type PluginContribution = StatusLine | ActionRow | CapabilityRequest | FormSection;
export interface PluginManifest {
  id: string; name: string; version: string; contractVersion: 2; type: 'js'; entryPoint: string;
  sourceId: string; sourceType: 'OFFICIAL' | 'THIRD_PARTY'; requiredCapabilities: Capability[];
  description?: string; contributions?: PluginContribution[];
}
export interface PluginEvent {
  kind: 'load' | 'action' | 'decision'; planId?: number; actionId?: string; fieldId?: string | null;
  configuration?: Record<string, string>;
  /** Form callbacks serialize switch booleans as 'true'/'false'; other values remain strings. */
  value?: string | null; packageName?: string | null; appName?: string | null;
  shouldTrack?: boolean; activeUntilMillis?: number;
}
export interface PluginResult { message: string; snapshot?: TodaySnapshot | null; }
export type PluginEntry = (event: PluginEvent, host: Host) => PluginResult;
