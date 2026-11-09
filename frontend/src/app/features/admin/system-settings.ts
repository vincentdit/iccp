import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api.service';
import { SystemFeature, SystemSetting, SystemSettingsSnapshot } from '../../core/models';
import { Session } from '../../core/session.service';
import { Toasts } from '../../core/toast.service';
import { PageHeader } from '../../ui/page-header';

@Component({selector:'app-system-settings',imports:[FormsModule,PageHeader],template:`
<div class="page page-narrow"><app-page-header title="System settings" subtitle="Enable functions, control menus and manage operational defaults." />
<div class="warning">Changes apply to all users immediately and are recorded in the audit trail.</div>
<h2>Modules and menus</h2><section class="card">@for(f of moduleFeatures();track f.code){<label class="row"><span><strong>{{label(f.code)}}</strong><small>{{description(f.code)}}</small></span><input type="checkbox" [checked]="f.enabled" (change)="toggle(f,$any($event.target).checked)" /></label>}</section>
@for(category of categories();track category){<h2>{{category}}</h2><section class="card">@for(s of byCategory(category);track s.code){<label class="row"><span><strong>{{s.name}}</strong><small>{{s.description}}</small></span>@switch(s.valueType){@case('BOOLEAN'){<input type="checkbox" [checked]="s.value" (change)="save(s,$any($event.target).checked)" />}@case('INTEGER'){<input class="number" type="number" min="1" max="1000" [ngModel]="s.value" (ngModelChange)="save(s,+$event)" />}@default{<input class="text" maxlength="500" [ngModel]="s.value" (change)="save(s,$any($event.target).value)" />}}</label>}</section>}
</div>`,styles:`.page-narrow{max-width:820px}.warning{padding:13px 16px;border-radius:14px;background:color-mix(in srgb,var(--orange) 14%,var(--surface-1));color:var(--label-2);margin-bottom:24px}.card{background:var(--surface-1);border:.5px solid var(--separator);border-radius:18px;overflow:hidden;margin-bottom:26px}.row{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:15px 17px;border-bottom:.5px solid var(--separator)}.row:last-child{border-bottom:0}.row span{display:grid;gap:3px}.row small{color:var(--label-2)}input[type=checkbox]{width:22px;height:22px;accent-color:var(--tint)}.number{width:100px}.text{width:min(300px,45vw)}input:not([type=checkbox]){padding:9px 11px;border:0;border-radius:10px;background:var(--fill-2);color:var(--label-1)}h2{font-size:var(--fs-title-3);margin:22px 4px 9px}`,
changeDetection:ChangeDetectionStrategy.OnPush})
export class SystemSettingsPage{
 private api=inject(Api);private toast=inject(Toasts);private session=inject(Session);protected data=signal<SystemSettingsSnapshot>({features:[],settings:[]});
 protected moduleFeatures=()=>this.data().features.filter(f=>f.code.startsWith('MODULE_'));protected categories=()=>[...new Set(this.data().settings.map(s=>s.category))];protected byCategory=(c:string)=>this.data().settings.filter(s=>s.category===c);
 constructor(){this.load()} private load(){this.api.get<SystemSettingsSnapshot>('/system-settings').subscribe({next:x=>this.data.set(x),error:e=>this.toast.error(e)})}
 protected toggle(f:SystemFeature,enabled:boolean){this.api.put('/system-settings/features/'+f.code,{enabled}).subscribe({next:()=>{this.data.update(d=>({...d,features:d.features.map(x=>x.code===f.code?{...x,enabled}:x)}));this.session.system.update(d=>({...d,features:d.features.map(x=>x.code===f.code?{...x,enabled}:x)}));this.toast.show('Feature setting saved')},error:e=>{this.toast.error(e);this.load()}})}
 protected save(s:SystemSetting,value:boolean|number|string){this.api.put('/system-settings/values/'+s.code,{value}).subscribe({next:()=>{this.data.update(d=>({...d,settings:d.settings.map(x=>x.code===s.code?{...x,value}:x)}));this.toast.show('System setting saved')},error:e=>{this.toast.error(e);this.load()}})}
 protected label(c:string){return ({MODULE_ANNOUNCEMENTS:'Announcements',MODULE_FINANCE:'Financial Diary',MODULE_DIRECTORY:'Staff directory',MODULE_NOTIFICATIONS:'Notifications',MODULE_WORK_HUB:'Work Hub',MODULE_MEETINGS:'Meetings',MODULE_HR_ADMIN:'HR administration'} as Record<string,string>)[c]??c}
 protected description(c:string){return 'Show this function in menus and allow its API operations.'}
}
