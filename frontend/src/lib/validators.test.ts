import { describe, expect, test } from 'vitest';
import {
  clampQty,
  validateCustomer,
  validateField,
  validateQuantities,
} from './validators';

describe('validateField', () => {
  test('name needs at least 3 characters after trimming', () => {
    expect(validateField('name', ' Al ')).toBe('errName');
    expect(validateField('name', 'Asha')).toBeNull();
  });

  test('mobile must be 10 digits starting 6-9', () => {
    expect(validateField('mobile', '1234567890')).toBe('errMobile');
    expect(validateField('mobile', '98765')).toBe('errMobile');
    expect(validateField('mobile', '9876543210')).toBeNull();
  });

  test('address needs at least 10 characters', () => {
    expect(validateField('address', 'Pune')).toBe('errAddress');
    expect(validateField('address', '12 MG Road, Pune')).toBeNull();
  });

  test('pin must be 6 digits; any Indian pin is accepted', () => {
    expect(validateField('pinCode', '41100')).toBe('errPin');
    expect(validateField('pinCode', '411030')).toBeNull();
    expect(validateField('pinCode', '400001')).toBeNull();
  });

  test('email is optional unless required, and must look like an email', () => {
    expect(validateField('email', '')).toBeNull();
    expect(validateField('email', '', { emailRequired: true })).toBe('errEmailRequired');
    expect(validateField('email', 'nope')).toBe('errEmail');
    expect(validateField('email', 'a@b.co')).toBeNull();
  });

  test('otp must be 6 digits', () => {
    expect(validateField('otp', '12345')).toBe('errOtp');
    expect(validateField('otp', '123456')).toBeNull();
  });
});

describe('validateCustomer', () => {
  test('returns one error key per failing field only', () => {
    const errors = validateCustomer(
      { name: 'Al', mobile: '9876543210', email: '', address: 'short', pinCode: '411001' },
    );
    expect(errors).toEqual({ name: 'errName', address: 'errAddress' });
  });

  test('is empty for a valid customer', () => {
    expect(
      validateCustomer({ name: 'Asha Patel', mobile: '9876543210', email: 'a@b.co', address: '12 MG Road, Pune', pinCode: '411001' }),
    ).toEqual({});
  });
});

describe('validateQuantities', () => {
  const limits = { maxPerItem: 20, maxTotal: 50 };

  test('needs at least one packet', () => {
    expect(validateQuantities({}, limits)).toBe('errNoPackets');
    expect(validateQuantities({ 1: 0 }, limits)).toBe('errNoPackets');
  });

  test('rejects fractions, negatives and per-item or total overflow', () => {
    expect(validateQuantities({ 1: 1.5 }, limits)).toBe('errQtyWhole');
    expect(validateQuantities({ 1: -1 }, limits)).toBe('errQtyWhole');
    expect(validateQuantities({ 1: 21 }, limits)).toBe('errQtyMaxItem');
    expect(validateQuantities({ 1: 20, 2: 20, 3: 20 }, limits)).toBe('errQtyMaxTotal');
    expect(validateQuantities({ 1: 2, 2: 3 }, limits)).toBeNull();
  });
});

describe('clampQty', () => {
  test('keeps whole numbers between 0 and the maximum', () => {
    expect(clampQty(-3, 20)).toBe(0);
    expect(clampQty(25, 20)).toBe(20);
    expect(clampQty(2.7, 20)).toBe(2);
    expect(clampQty(Number.NaN, 20)).toBe(0);
  });
});
