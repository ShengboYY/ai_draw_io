/** @typedef {'overview' | 'runs' | 'evalRuns' | 'operations' | 'candidates' | 'cases' | 'datasets' | 'trace'} AdminSection */
/** @typedef {'overview' | 'traces' | 'evaluation' | 'operations'} PrimaryNavId */
/** @typedef {{ id: PrimaryNavId; href: string; label: string; sections: AdminSection[] }} PrimaryNavItem */

/** @type {PrimaryNavItem[]} */
export const primaryNavItems = [
  { id: 'overview', href: '/admin', label: 'Overview', sections: ['overview'] },
  { id: 'traces', href: '/admin/runs', label: 'Traces', sections: ['runs', 'trace'] },
  { id: 'evaluation', href: '/admin/evaluations', label: 'Evaluation', sections: ['evalRuns', 'candidates', 'cases', 'datasets'] },
  { id: 'operations', href: '/admin/eval-operations', label: 'Operations', sections: ['operations'] },
];

/** @param {AdminSection} active @returns {PrimaryNavId} */
export function primaryNavIdFor(active) {
  return primaryNavItems.find((item) => item.sections.includes(active))?.id || 'overview';
}
