import { describe, expect, it } from 'vitest';
import { orderIdValid, stateLabel, elapsedLabel } from './contracts';
describe('console contracts', () => {
  it('rejects invalid identifiers', () => { expect(orderIdValid('order-1')).toBe(true); expect(orderIdValid('a b')).toBe(false); expect(orderIdValid('')).toBe(false); });
  it('makes intervention state explicit', () => { expect(stateLabel('MANUAL_REVIEW')).toBe('Manual review required'); expect(stateLabel('unrecognized')).toBe('Unknown state'); });
  it('displays age without a negative clock difference', () => { expect(elapsedLabel('1000', 42000)).toBe('41s ago'); expect(elapsedLabel('10000', 1000)).toBe('0s ago'); });
});
