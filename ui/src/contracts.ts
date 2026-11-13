export function orderIdValid(value: string) { return /^[A-Za-z0-9_.:-]{1,128}$/.test(value); }
export function stateLabel(value: string) {
  return ({ RESERVING: 'Waiting for reservation', CHARGING: 'Waiting for charge', RELEASING: 'Releasing reservation', COMPLETED: 'Completed', COMPENSATED: 'Compensated', FAILED: 'Failed', MANUAL_REVIEW: 'Manual review required' } as Record<string, string>)[value] ?? 'Unknown state';
}
export function elapsedLabel(updatedAtMs: string, now: number) {
  const elapsed = Math.max(0, Math.floor((now - Number(updatedAtMs)) / 1000));
  if (!Number.isFinite(elapsed)) return 'Unknown age';
  return elapsed < 60 ? `${elapsed}s ago` : `${Math.floor(elapsed / 60)}m ago`;
}
