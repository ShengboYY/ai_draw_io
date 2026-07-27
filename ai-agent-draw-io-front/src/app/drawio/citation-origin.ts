export const citationOriginLabel = (origin: string): string => {
  // Direct cell citations describe topology copied from the user's uploaded source image.
  if (origin === 'DIRECT_ATTACHMENT') return 'original image';
  return origin.toLowerCase().replaceAll('_', ' ');
};
