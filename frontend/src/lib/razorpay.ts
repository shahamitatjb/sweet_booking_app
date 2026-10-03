/** Razorpay Standard Checkout glue. Pure option-building is separated so it can be unit tested. */

export const RAZORPAY_CHECKOUT_SRC = 'https://checkout.razorpay.com/v1/checkout.js';

export type CheckoutSuccess = { razorpayOrderId: string; razorpayPaymentId: string; razorpaySignature: string };

type RazorpayResponse = { razorpay_order_id: string; razorpay_payment_id: string; razorpay_signature: string };

export type CheckoutOptions = {
  key: string;
  order_id: string;
  amount: number;
  currency: 'INR';
  name: string;
  description: string;
  prefill: { name: string; contact: string; email?: string };
  handler: (response: RazorpayResponse) => void;
  modal: { ondismiss: () => void };
  theme: { color: string };
};

type BuildArgs = {
  keyId: string;
  gatewayOrderId: string;
  amountPaise: number;
  title: string;
  description: string;
  customer: { name: string; mobile: string; email: string };
  onSuccess: (result: CheckoutSuccess) => void;
  onDismiss: () => void;
};

const THEME_MAROON = '#7a1f1f';

export function buildCheckoutOptions(args: BuildArgs): CheckoutOptions {
  const prefill: CheckoutOptions['prefill'] = { name: args.customer.name, contact: args.customer.mobile };
  if (args.customer.email) prefill.email = args.customer.email;
  return {
    key: args.keyId,
    order_id: args.gatewayOrderId,
    amount: args.amountPaise,
    currency: 'INR',
    name: args.title,
    description: args.description,
    prefill,
    handler: (r) =>
      args.onSuccess({
        razorpayOrderId: r.razorpay_order_id,
        razorpayPaymentId: r.razorpay_payment_id,
        razorpaySignature: r.razorpay_signature,
      }),
    modal: { ondismiss: args.onDismiss },
    theme: { color: THEME_MAROON },
  };
}

type RazorpayInstance = { open: () => void; on: (event: 'payment.failed', cb: (e: unknown) => void) => void };
type RazorpayCtor = new (options: CheckoutOptions) => RazorpayInstance;

declare global {
  interface Window {
    Razorpay?: RazorpayCtor;
  }
}

/** Injects checkout.js once; resolves false if the script cannot load (offline, blocked). */
export function loadRazorpayScript(): Promise<boolean> {
  if (window.Razorpay) return Promise.resolve(true);
  return new Promise((resolve) => {
    // A failed earlier attempt leaves no element behind (removed below), so a fresh tag is always
    // created here and its load/error events are guaranteed to fire for this listener.
    const script = document.createElement('script');
    const finish = (ok: boolean) => {
      if (!ok) script.remove();
      resolve(ok);
    };
    script.addEventListener('load', () => finish(!!window.Razorpay), { once: true });
    script.addEventListener('error', () => finish(false), { once: true });
    script.src = RAZORPAY_CHECKOUT_SRC;
    script.async = true;
    document.body.appendChild(script);
  });
}

/** Opens the hosted checkout; `onFailure` fires when Razorpay reports a failed attempt (modal stays open). */
export function openCheckout(options: CheckoutOptions, onFailure: () => void): void {
  if (!window.Razorpay) throw new Error('Razorpay checkout not loaded');
  const rzp = new window.Razorpay(options);
  rzp.on('payment.failed', onFailure);
  rzp.open();
}
