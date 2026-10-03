import { describe, expect, test, vi } from 'vitest';
import { buildCheckoutOptions, type CheckoutSuccess } from './razorpay';

const base = {
  keyId: 'rzp_test_key',
  gatewayOrderId: 'order_abc',
  amountPaise: 45000,
  title: 'Shri Trust',
  description: 'Diwali sweets booking',
  customer: { name: 'Asha Patel', mobile: '9876543210', email: 'asha@example.com' },
};

describe('buildCheckoutOptions', () => {
  test('maps order and key into Razorpay checkout fields', () => {
    const opts = buildCheckoutOptions({ ...base, onSuccess: () => undefined, onDismiss: () => undefined });

    expect(opts.key).toBe('rzp_test_key');
    expect(opts.order_id).toBe('order_abc');
    expect(opts.amount).toBe(45000);
    expect(opts.currency).toBe('INR');
    expect(opts.name).toBe('Shri Trust');
    expect(opts.prefill).toEqual({ name: 'Asha Patel', contact: '9876543210', email: 'asha@example.com' });
  });

  test('handler forwards the three Razorpay response fields to onSuccess', () => {
    const onSuccess = vi.fn<(r: CheckoutSuccess) => void>();
    const opts = buildCheckoutOptions({ ...base, onSuccess, onDismiss: () => undefined });

    opts.handler({ razorpay_order_id: 'order_abc', razorpay_payment_id: 'pay_1', razorpay_signature: 'sig' });

    expect(onSuccess).toHaveBeenCalledWith({ razorpayOrderId: 'order_abc', razorpayPaymentId: 'pay_1', razorpaySignature: 'sig' });
  });

  test('closing the modal calls onDismiss', () => {
    const onDismiss = vi.fn();
    const opts = buildCheckoutOptions({ ...base, onSuccess: () => undefined, onDismiss });

    opts.modal.ondismiss();

    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  test('omits empty email from prefill so Razorpay does not show a blank field error', () => {
    const opts = buildCheckoutOptions({
      ...base,
      customer: { name: 'Asha', mobile: '9876543210', email: '' },
      onSuccess: () => undefined,
      onDismiss: () => undefined,
    });

    expect(opts.prefill).toEqual({ name: 'Asha', contact: '9876543210' });
  });
});
