import Image from 'next/image';
import Link from 'next/link';

type IconProps = {
  className?: string;
};

type BrandMarkProps = IconProps & {
  // Retained for API compatibility with existing call sites; the logo art now
  // carries its own padding, so this is no longer applied to an inner icon.
  iconClassName?: string;
};

// Keep the auth logo consistent across sign-in, sign-up, and follow-up states.
export function AuthBrandMark({ className = '' }: BrandMarkProps) {
  return (
    // Client-side home navigation leaves the authenticated session cookie untouched.
    <Link href="/" aria-label="FreeDraw home" className="block">
      <span
        className={`relative block overflow-hidden shadow-[0_18px_45px_rgba(52,51,61,0.18)] ${className}`}
        aria-hidden="true"
      >
        <Image
          src="/brand/freedraw-app-icon-v2.png"
          alt=""
          fill
          sizes="48px"
          className="object-cover"
          priority
        />
      </span>
    </Link>
  );
}

export function EnvelopeIcon({ className = '' }: IconProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
    >
      <rect x="3.5" y="5.5" width="17" height="13" rx="2.5" />
      <path d="m5 8 7 5 7-5" />
    </svg>
  );
}

export function LockIcon({ className = '' }: IconProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
    >
      <rect x="5" y="10" width="14" height="10" rx="2.5" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3" />
    </svg>
  );
}
