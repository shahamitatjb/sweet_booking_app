/** Staff roles, in the order the staff table lists them. Mirrors Staff.Role on the API. */
export const ROLES = ['SUPER_ADMIN', 'ADMIN', 'TREASURER', 'COUNTER'] as const;
export type Role = (typeof ROLES)[number];

/** Settings (texts, limits, catalogue, staff, danger zone). */
export const canConfigure = (role?: string) => role === 'SUPER_ADMIN';

/** Dashboard, bookings, export and void. */
export const canSeeDashboard = (role?: string) => role === 'SUPER_ADMIN' || role === 'ADMIN' || role === 'TREASURER';

/** Marking payments reconciled against Razorpay and bank statements. */
export const canReconcile = (role?: string) => role === 'SUPER_ADMIN' || role === 'TREASURER';
