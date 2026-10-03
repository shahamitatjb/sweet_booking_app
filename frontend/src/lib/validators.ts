/**
 * Field rules for the booking forms. They mirror OrderService.validateCustomer on the
 * API exactly; the server remains the final check. Every function returns an i18n key
 * (see i18n/dicts.ts) or null when the value is acceptable.
 */
export type FieldName = 'name' | 'mobile' | 'email' | 'address' | 'pinCode' | 'otp';

export type CustomerForm = {
  name: string;
  mobile: string;
  email: string;
  address: string;
  pinCode: string;
};

export type FieldOptions = {
  emailRequired?: boolean;
};

export type QuantityLimits = { maxPerItem: number; maxTotal: number };

const MOBILE = /^[6-9]\d{9}$/;
const PIN = /^\d{6}$/;
const OTP = /^\d{6}$/;
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

const MIN_NAME = 3;
const MIN_ADDRESS = 10;

export function validateField(field: FieldName, raw: string, opts: FieldOptions = {}): string | null {
  const value = (raw || '').trim();
  switch (field) {
    case 'name':
      return value.length >= MIN_NAME ? null : 'errName';
    case 'mobile':
      return MOBILE.test(value) ? null : 'errMobile';
    case 'address':
      return value.length >= MIN_ADDRESS ? null : 'errAddress';
    case 'pinCode':
      return PIN.test(value) ? null : 'errPin';
    case 'email':
      if (!value) return opts.emailRequired ? 'errEmailRequired' : null;
      return EMAIL.test(value) ? null : 'errEmail';
    case 'otp':
      return OTP.test(value) ? null : 'errOtp';
    default:
      return null;
  }
}

export const CUSTOMER_FIELDS: ReadonlyArray<keyof CustomerForm> = ['name', 'mobile', 'email', 'address', 'pinCode'];

export function validateCustomer(form: CustomerForm, opts: FieldOptions = {}): Partial<Record<FieldName, string>> {
  return CUSTOMER_FIELDS.reduce<Partial<Record<FieldName, string>>>((errors, field) => {
    const key = validateField(field, form[field], opts);
    return key ? { ...errors, [field]: key } : errors;
  }, {});
}

export function validateQuantities(qty: Record<number, number>, limits: QuantityLimits): string | null {
  const values = Object.values(qty);
  if (values.some((n) => !Number.isInteger(n) || n < 0)) return 'errQtyWhole';
  if (values.some((n) => n > limits.maxPerItem)) return 'errQtyMaxItem';
  const total = values.reduce((sum, n) => sum + n, 0);
  if (total < 1) return 'errNoPackets';
  if (total > limits.maxTotal) return 'errQtyMaxTotal';
  return null;
}

export function clampQty(n: number, max: number): number {
  if (!Number.isFinite(n)) return 0;
  return Math.min(max, Math.max(0, Math.trunc(n)));
}
