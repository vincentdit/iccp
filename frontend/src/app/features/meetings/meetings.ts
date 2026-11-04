import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api.service';
import { I18n } from '../../core/i18n.service';
import { MeetingSummary } from '../../core/models';
import { Session } from '../../core/session.service';
import { Empty } from '../../ui/empty';
import { Icon } from '../../ui/icon';
import { PageHeader } from '../../ui/page-header';
import { Segmented } from '../../ui/segmented';
import { Status } from '../../ui/status';

@Component({selector:'app-meetings',imports:[RouterLink,PageHeader,Icon,Empty,Segmented,Status],template:`
<div class="page">
  <app-page-header title="Meetings" subtitle="Your schedule, agendas and responses in one place.">
    @if(session.can()?.manageMeetings){<a class="btn btn-primary" routerLink="/meetings/new"><app-icon name="plus"/> Schedule meeting</a>}
  </app-page-header>
  <app-segmented [segments]="segments" [(value)]="view" />
  <div class="meeting-grid">
    @for(m of visible();track m.publicId){
      <a class="meeting-card card card-raised" [routerLink]="['/meetings',m.publicId]">
        <div class="date-tile"><strong>{{day(m.startsAt)}}</strong><span>{{month(m.startsAt)}}</span></div>
        <div class="grow"><div class="eyebrow">{{type(m)}}</div><h2>{{m.title}}</h2>
          <p><app-icon name="clock"/> {{i18n.dateTime(m.startsAt)}}</p>
          <p><app-icon [name]="m.mode==='ONLINE'?'external':'pin'"/> {{m.venue||m.mode}}</p>
          <div class="meta"><app-status [value]="m.myRsvp||'PENDING'"/><span>{{m.participantCount}} participants</span></div>
        </div><app-icon class="chev" name="chevronRight"/>
      </a>
    } @empty { @if(loaded()){<app-empty icon="calendar" title="No meetings here" text="Meetings you are invited to will appear here."/>} }
  </div>
</div>`,styles:`
.meeting-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(330px,1fr));gap:16px;margin-top:18px}.meeting-card{display:flex;gap:16px;padding:20px;text-decoration:none;color:inherit;transition:transform .22s ease,box-shadow .22s ease}.meeting-card:hover{transform:translateY(-2px)}.date-tile{width:58px;height:64px;border-radius:16px;background:var(--fill);display:grid;place-content:center;text-align:center;color:var(--accent);flex:none}.date-tile strong{font-size:24px;line-height:1}.date-tile span,.eyebrow{font-size:11px;text-transform:uppercase;font-weight:700;letter-spacing:.06em}.eyebrow{color:var(--label-2)}h2{font-size:var(--fs-title3);margin:4px 0 10px}p{display:flex;align-items:center;gap:7px;margin:5px 0;color:var(--label-2);font-size:var(--fs-footnote);--icon-size:15px}.meta{display:flex;align-items:center;gap:10px;margin-top:13px;font-size:var(--fs-caption);color:var(--label-2)}.chev{align-self:center;color:var(--label-3)}@media(max-width:600px){.meeting-grid{grid-template-columns:1fr}.meeting-card{padding:16px}}
`,changeDetection:ChangeDetectionStrategy.OnPush})
export class MeetingsPage{
 private api=inject(Api); protected i18n=inject(I18n); protected session=inject(Session); protected meetings=signal<MeetingSummary[]>([]); protected loaded=signal(false); protected view=signal('upcoming');
 protected segments=[{value:'upcoming',label:'Upcoming'},{value:'past',label:'Past'}];
 protected visible=computed(()=>{const now=Date.now();return this.meetings().filter(m=>this.view()==='upcoming'?new Date(m.endsAt).getTime()>=now:new Date(m.endsAt).getTime()<now)});
 constructor(){this.api.get<MeetingSummary[]>('/mtg/meetings').subscribe({next:m=>{this.meetings.set(m);this.loaded.set(true)},error:()=>this.loaded.set(true)})}
 protected day(v:string){return new Intl.DateTimeFormat(this.i18n.lang(),{day:'2-digit'}).format(new Date(v))} protected month(v:string){return new Intl.DateTimeFormat(this.i18n.lang(),{month:'short'}).format(new Date(v))} protected type(m:MeetingSummary){return this.i18n.lang()==='sw'?m.typeNameSw:m.typeNameEn}
}
