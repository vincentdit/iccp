import { Routes } from '@angular/router';
import { canGuard, featureGuard, meetingsSystemGuard, signedInGuard } from './core/guards';
import { Shell } from './shell/shell';

export const routes: Routes = [
  { path: 'sign-in', loadComponent: () => import('./features/auth/sign-in').then((m) => m.SignIn), title: 'Sign in – ICCP' },
  { path: 'auth/callback', loadComponent: () => import('./features/auth/callback').then((m) => m.AuthCallback) },
  {
    path: '',
    component: Shell,
    canActivate: [signedInGuard],
    children: [
      { path: '', loadComponent: () => import('./features/home/home').then((m) => m.Home), title: 'Home – ICCP' },
      {
        path: 'announcements', canActivate: [featureGuard('MODULE_ANNOUNCEMENTS')],
        children: [
          { path: '', loadComponent: () => import('./features/announcements/feed').then((m) => m.Feed), title: 'Announcements – ICCP' },
          {
            path: 'manage',
            canActivate: [canGuard('publishAnnouncements', 'approveAnnouncements')],
            loadComponent: () => import('./features/announcements/manage').then((m) => m.Manage),
            title: 'Manage notices – ICCP',
          },
          {
            path: 'manage/new',
            canActivate: [canGuard('publishAnnouncements')],
            loadComponent: () => import('./features/announcements/compose').then((m) => m.Compose),
            title: 'New notice – ICCP',
          },
          {
            path: 'manage/:id',
            canActivate: [canGuard('publishAnnouncements', 'approveAnnouncements', 'viewReach')],
            loadComponent: () => import('./features/announcements/manage-detail').then((m) => m.ManageDetailPage),
          },
          {
            path: 'manage/:id/edit',
            canActivate: [canGuard('publishAnnouncements')],
            loadComponent: () => import('./features/announcements/compose').then((m) => m.Compose),
          },
          { path: ':id', loadComponent: () => import('./features/announcements/detail').then((m) => m.Detail) },
        ],
      },
      {
        path: 'finance', canActivate: [featureGuard('MODULE_FINANCE')],
        children: [
          { path: '', loadComponent: () => import('./features/finance/diary').then((m) => m.Diary), title: 'Financial Diary – ICCP' },
          { path: 'claims/:id', loadComponent: () => import('./features/finance/claim').then((m) => m.ClaimPage) },
          { path: 'queries/:id', loadComponent: () => import('./features/finance/query-thread').then((m) => m.QueryThread) },
          {
            path: 'workspace',
            canActivate: [canGuard('financeWorkspace')],
            loadComponent: () => import('./features/finance/workspace').then((m) => m.Workspace),
            title: 'Finance workspace – ICCP',
          },
          {
            path: 'batches/:id',
            canActivate: [canGuard('financeWorkspace')],
            loadComponent: () => import('./features/finance/batch').then((m) => m.BatchPage),
          },
          {
            path: 'desk/:id',
            canActivate: [canGuard('financeWorkspace')],
            loadComponent: () => import('./features/finance/query-thread').then((m) => m.QueryThread),
          },
        ],
      },
      {
        path: 'directory', canActivate: [featureGuard('MODULE_DIRECTORY')],
        children: [
          { path: '', loadComponent: () => import('./features/directory/directory').then((m) => m.Directory), title: 'Directory – ICCP' },
          { path: 'organisation', loadComponent: () => import('./features/directory/org-chart').then((m) => m.OrgChart), title: 'Organisation – ICCP' },
          { path: 'me', loadComponent: () => import('./features/directory/profile').then((m) => m.Profile), title: 'My profile – ICCP' },
          { path: ':id', loadComponent: () => import('./features/directory/profile').then((m) => m.Profile) },
        ],
      },
      { path: 'notifications', canActivate:[featureGuard('MODULE_NOTIFICATIONS')], loadComponent: () => import('./features/notifications/inbox').then((m) => m.InboxPage), title: 'Notifications – ICCP' },
      { path: 'work', canActivate:[featureGuard('MODULE_WORK_HUB')], loadComponent: () => import('./features/work/work-hub').then((m) => m.WorkHub), title: 'Work hub - ICCP' },
      {
        path: 'meetings', canActivate: [featureGuard('MODULE_MEETINGS'),meetingsSystemGuard], children: [
          { path: '', loadComponent: () => import('./features/meetings/meetings').then((m) => m.MeetingsPage), title: 'Meetings – ICCP' },
          { path: 'new', canActivate: [canGuard('manageMeetings')], loadComponent: () => import('./features/meetings/schedule').then((m) => m.ScheduleMeeting), title: 'Schedule meeting – ICCP' },
          { path: ':id/minutes', loadComponent: () => import('./features/meetings/minutes').then((m) => m.MinutesPage), title: 'Meeting minutes – ICCP' },
          { path: ':id', loadComponent: () => import('./features/meetings/meeting-detail').then((m) => m.MeetingDetailPage), title: 'Meeting – ICCP' },
        ],
      },
      {
        path: 'admin/hr',
        canActivate: [featureGuard('MODULE_HR_ADMIN'),canGuard('hrAdmin')],
        loadComponent: () => import('./features/admin/hr').then((m) => m.HrAdmin),
        title: 'HR records – ICCP',
      },
      {path:'admin/system-settings',canActivate:[canGuard('systemAdmin')],loadComponent:()=>import('./features/admin/system-settings').then(m=>m.SystemSettingsPage),title:'System settings – ICCP'},
      {
        path: 'admin/security',
        canActivate: [canGuard('securityAdmin', 'auditSearch')],
        loadComponent: () => import('./features/admin/security').then((m) => m.SecurityAdmin),
        title: 'Access and audit – ICCP',
      },
      { path: 'settings', loadComponent: () => import('./features/settings/settings').then((m) => m.SettingsPage), title: 'Settings – ICCP' },
      { path: 'more', loadComponent: () => import('./features/settings/more').then((m) => m.More), title: 'More – ICCP' },
    ],
  },
  { path: '**', redirectTo: '' },
];
