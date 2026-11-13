import type { PageC } from './types';
export interface SettlementTotals {
  saleCount: number;
  saleSellerAmount: number;
  refundCount: number;
  refundSellerAmount: number;
  netSellerAmount: number;
}
export interface SettlementPreview {
  status: 'INITIALIZING' | 'READY' | 'STALE';
  buildId: string | null;
  generation: number;
  from: string;
  to: string;
  totals: SettlementTotals | null;
  days: PageC<{ businessDate: string; scheduleId: number; totals: SettlementTotals }>;
  health: {
    unpublished: number;
    unapplied: number;
    errors: number;
    originPending: number;
    unposted: number;
    pendingRefunds: number;
    lastFanoutAt: string | null;
    verifiedAt: string | null;
  } | null;
}
