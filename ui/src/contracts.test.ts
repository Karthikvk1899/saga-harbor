import { describe, expect, it } from 'vitest';
import { orderIdValid, stateLabel } from './contracts';
describe('console contracts', () => {
  it('rejects invalid identifiers', () => { expect(orderIdValid('order-1')).toBe(true); expect(orderIdValid('a b')).toBe(false); expect(orderIdValid('')).toBe(false); });
  it('makes intervention state explicit', () => { expect(stateLabel('MANUAL_REVIEW')).toBe('Manual review required'); expect(stateLabel('unrecognized')).toBe('Unknown state'); });
});
