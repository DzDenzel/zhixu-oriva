/** Pure ES2020 helpers. Authority always stays with the native capability dispatcher. */
export class HostError extends Error {
  constructor(message) { super(message); this.name = 'HostError'; }
}
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const required = (condition, message) => { if (!condition) throw new HostError(message); };
const nonblank = value => typeof value === 'string' && value.trim().length > 0;

export function createHost(host) {
  required(host && typeof host.postMessage === 'function', 'host.postMessage is required');
  const request = message => {
    required(object(message) && object(message.args), 'Request must contain op and args');
    const args = message.args;
    switch (message.op) {
      case 'query.today':
        required(args.date === undefined || (typeof args.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(args.date)), 'date must be yyyy-MM-dd'); break;
      case 'query.plan': required(Number.isSafeInteger(args.id) && args.id !== 0, 'plan id must be a nonzero safe integer'); break;
      case 'query.report': required(['day', 'week', 'month'].includes(args.period), 'Unknown report period'); break;
      case 'feedback': required(nonblank(args.code), 'feedback code is required'); break;
      case 'overlay':
        required(nonblank(args.text), 'overlay text is required');
        required(args.displayMillis === undefined || (Number.isSafeInteger(args.displayMillis) && args.displayMillis > 0), 'overlay displayMillis must be positive'); break;
      case 'home': case 'haptic': break;
      default: throw new HostError(`Unknown operation: ${message.op}`);
    }
    const raw = host.postMessage(JSON.stringify(message));
    required(typeof raw === 'string', 'Host response must be a JSON string');
    let response;
    try { response = JSON.parse(raw); } catch { throw new HostError('Host response is invalid JSON'); }
    required(object(response) && typeof response.ok === 'boolean', 'Host response must contain boolean ok');
    if (!response.ok) {
      required(typeof response.error === 'string', 'Host error must be a string');
      throw new HostError(response.error);
    }
    required(Object.prototype.hasOwnProperty.call(response, 'data'), 'Host response is missing data');
    return response.data;
  };
  return {
    request,
    overlay: (text, displayMillis) => request({ op: 'overlay', args: { text, ...(displayMillis === undefined ? {} : { displayMillis }) } }),
    home: () => request({ op: 'home', args: {} }),
    haptic: () => request({ op: 'haptic', args: {} }),
    feedback: code => request({ op: 'feedback', args: { code } }),
    query: {
      today: date => request({ op: 'query.today', args: date === undefined ? {} : { date } }),
      plan: id => request({ op: 'query.plan', args: { id } }),
      report: period => request({ op: 'query.report', args: { period } }),
    },
  };
}
