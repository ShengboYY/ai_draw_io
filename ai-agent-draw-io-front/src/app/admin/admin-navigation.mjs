/** @typedef {'overview' | 'runs' | 'evalOverview' | 'evalRuns' | 'operations' | 'candidates' | 'cases' | 'datasets' | 'trace'} AdminSection */
/** @typedef {'overview' | 'evaluation' | 'traceAnalysis' | 'operations'} PrimaryNavId */
/** @typedef {{ id: PrimaryNavId; href: string; label: string; sections: AdminSection[] }} PrimaryNavItem */

/** @type {PrimaryNavItem[]} */
export const primaryNavItems = [
  { id: 'overview', href: '/admin', label: 'Overview', sections: ['overview'] },
  { id: 'evaluation', href: '/admin/evaluations', label: 'Evaluation', sections: ['evalOverview', 'evalRuns', 'cases', 'datasets'] },
  { id: 'traceAnalysis', href: '/admin/runs', label: 'Trace Analysis', sections: ['runs', 'trace', 'candidates'] },
  { id: 'operations', href: '/admin/eval-operations', label: 'Operations', sections: ['operations'] },
];

/** @param {AdminSection} active @returns {PrimaryNavId} */
export function primaryNavIdFor(active) {
  return primaryNavItems.find((item) => item.sections.includes(active))?.id || 'overview';
}
