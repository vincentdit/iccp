import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api.service';
import { Page, StaffSummary } from '../../core/models';
import { Toasts } from '../../core/toast.service';
import { Session } from '../../core/session.service';
import { Empty } from '../../ui/empty';
import { PageHeader } from '../../ui/page-header';
import { Segmented } from '../../ui/segmented';
import { Status } from '../../ui/status';

interface TaskRow { publicId:string; referenceNo:string; title:string; description?:string; priority:string; assigneeName:string; dueOn:string; status:string; progressPct:number; canAct:boolean; canRequestExtension:boolean; canDecideExtension:boolean; extensionPending:boolean; }
interface Conversation { publicId:string; title:string; type:string; ownerName:string; updatedAt:string; memberCount:number; lastMessage:string; unreadCount:number; muted:boolean; }
interface Message { id:number; senderName:string; body:string; createdAt:string; mine:boolean; }
interface DocumentRow { publicId:string; title:string; typeName:string; classification:string; language:string; status:string; ownerUnit:string; reviewDueOn?:string; updatedAt:string; }
interface DocumentType { code:string; nameEn:string; nameSw:string; }
interface Workspace { publicId:string; code:string; title:string; type:string; leadName:string; unitName:string; startsOn:string; endsOn?:string; status:string; confidential:boolean; milestones:number; overdueMilestones:number; }
interface RequestType { id:number; code:string; nameEn:string; category:string; slaHours:number; }
interface RequestRow { publicId:string; referenceNo:string; typeName:string; category:string; status:string; summary:string; submittedAt:string; slaDueAt:string; requesterName:string; agentName?:string; }
interface Offering { publicId:string; title:string; provider:string; mode:string; startsOn?:string; endsOn?:string; location?:string; deadline?:string; status:string; applicationStatus?:string; }
interface Metric { code:string; label:string; value:number; unit:string; }
interface Dashboard { asAt:string; metrics:Metric[]; }
interface Suggestion { publicId:string; category:string; description:string; status:string; response?:string; createdAt:string; }

@Component({
  selector: 'app-work-hub',
  imports: [DatePipe, FormsModule, PageHeader, Segmented, Status, Empty],
  templateUrl: './work-hub.html',
  styleUrl: './work-hub.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WorkHub {
  private api=inject(Api); private toasts=inject(Toasts); protected session=inject(Session);
  protected tab=signal('tasks');
  protected tabs=[
    {value:'tasks',label:'Tasks'},{value:'messages',label:'Messages'},{value:'documents',label:'Documents'},
    {value:'projects',label:'Projects'},{value:'requests',label:'Requests'},{value:'training',label:'Training'},
    {value:'analytics',label:'Analytics'},{value:'feedback',label:'Feedback'}
  ];
  protected busy=signal(false); protected composing=signal(false);
  protected tasks=signal<TaskRow[]>([]); protected conversations=signal<Conversation[]>([]); protected messages=signal<Message[]>([]);
  protected documents=signal<DocumentRow[]>([]); protected documentTypes=signal<DocumentType[]>([]);
  protected projects=signal<Workspace[]>([]); protected requestTypes=signal<RequestType[]>([]); protected requests=signal<RequestRow[]>([]); protected requestQueue=signal<RequestRow[]>([]);
  protected offerings=signal<Offering[]>([]); protected dashboard=signal<Dashboard|null>(null); protected suggestions=signal<Suggestion[]>([]);
  protected staff=signal<StaffSummary[]>([]); protected activeConversation=signal<Conversation|null>(null);
  protected title=computed(()=>this.tabs.find(x=>x.value===this.tab())?.label??'Work hub');
  protected subtitle=computed(()=>({
    tasks:'Directives, assignments and evidence',messages:'Secure staff conversations',documents:'Controlled policies, SOPs and templates',
    projects:'Research and consultancy workspaces',requests:'Internal services and approvals',training:'Opportunities and development records',
    analytics:'Management measures across ICCP',feedback:'Suggestions, surveys and service feedback'
  } as Record<string,string>)[this.tab()]);

  protected task={title:'',description:'',priority:'NORMAL',assigneeStaffPublicId:'',dueOn:''};
  protected conversation={title:'',memberPublicId:'',body:''};
  protected document={title:'',typeCode:'POLICY',classification:'INTERNAL',language:'en'};
  protected today=new Date().toISOString().slice(0,10);
  protected openTasks=computed(()=>this.tasks().filter(t=>!['CLOSED','CANCELLED'].includes(t.status)).length);
  protected overdueTasks=computed(()=>this.tasks().filter(t=>t.dueOn<this.today&&!['CLOSED','CANCELLED'].includes(t.status)).length);
  protected project={code:'',title:'',type:'INTERNAL',startsOn:''};
  protected request={requestTypeId:0,summary:'',description:''};
  protected feedback={category:'Service improvement',description:''};
  protected trainingJustification='';
  protected extensionTask=signal<TaskRow|null>(null); protected extensionDue=''; protected extensionReason='';
  protected canWorkQueue=computed(()=>this.session.me()?.roles.some(r=>['SERVICE_AGENT','DIRECTOR','EXECUTIVE'].includes(r))??false);

  constructor(){
    this.api.get<Page<StaffSummary>>('/dir/staff',{size:100}).subscribe(p=>this.staff.set(p.items));
    effect(()=>this.load(this.tab()));
  }

  protected load(tab:string){
    this.busy.set(true);
    const done=()=>this.busy.set(false), fail=(e:unknown)=>{done();this.toasts.error(e);};
    if(tab==='tasks')this.api.get<TaskRow[]>('/tsk/tasks').subscribe({next:x=>{this.tasks.set(x);done();},error:fail});
    if(tab==='messages')this.api.get<Conversation[]>('/msg/conversations').subscribe({next:x=>{this.conversations.set(x);done();},error:fail});
    if(tab==='documents'){
      this.api.get<DocumentType[]>('/doc/types').subscribe(x=>this.documentTypes.set(x));
      this.api.get<DocumentRow[]>('/doc/documents').subscribe({next:x=>{this.documents.set(x);done();},error:fail});
    }
    if(tab==='projects')this.api.get<Workspace[]>('/prj/workspaces').subscribe({next:x=>{this.projects.set(x);done();},error:fail});
    if(tab==='requests'){
      this.api.get<RequestType[]>('/req/types').subscribe(x=>{this.requestTypes.set(x);if(!this.request.requestTypeId&&x.length)this.request.requestTypeId=x[0].id;});
      this.api.get<RequestRow[]>('/req/requests').subscribe({next:x=>{this.requests.set(x);done();},error:fail});
      if(this.canWorkQueue())this.api.get<RequestRow[]>('/req/queue').subscribe({next:x=>this.requestQueue.set(x),error:e=>this.toasts.error(e)});
    }
    if(tab==='training')this.api.get<Offering[]>('/trn/offerings').subscribe({next:x=>{this.offerings.set(x);done();},error:fail});
    if(tab==='analytics')this.api.get<Dashboard>('/ana/dashboard').subscribe({next:x=>{this.dashboard.set(x);done();},error:fail});
    if(tab==='feedback')this.api.get<Suggestion[]>('/fdb/suggestions').subscribe({next:x=>{this.suggestions.set(x);done();},error:fail});
  }

  protected create(){
    const tab=this.tab(); let endpoint=''; let body:unknown={};
    if(tab==='tasks'){endpoint='/tsk/tasks';body={...this.task,source:'AD_HOC',verifierStaffPublicId:null,evidenceRequired:false};}
    if(tab==='messages'){endpoint='/msg/conversations';body={title:this.conversation.title,memberPublicIds:this.conversation.memberPublicId?[this.conversation.memberPublicId]:[]};}
    if(tab==='documents'){endpoint='/doc/documents';body={...this.document,keywords:[]};}
    if(tab==='projects'){endpoint='/prj/workspaces';body={...this.project,confidential:false};}
    if(tab==='requests'){endpoint='/req/requests';body={requestTypeId:this.request.requestTypeId,formData:{summary:this.request.summary,description:this.request.description}};}
    if(tab==='feedback'){endpoint='/fdb/suggestions';body={...this.feedback};}
    if(!endpoint)return;
    this.busy.set(true);
    this.api.post(endpoint,body).subscribe({next:()=>{this.composing.set(false);this.toasts.show('Saved successfully');this.reset(tab);this.load(tab);},error:e=>{this.busy.set(false);this.toasts.error(e);}});
  }

  protected transition(task:TaskRow,status:string){
    this.api.post('/tsk/tasks/'+task.publicId+'/transition',{status,comment:''}).subscribe({next:()=>this.load('tasks'),error:e=>this.toasts.error(e)});
  }
  protected openConversation(c:Conversation){
    this.activeConversation.set(c);this.api.get<Message[]>('/msg/conversations/'+c.publicId+'/messages').subscribe(x=>this.messages.set(x));
    if(c.unreadCount)this.api.post('/msg/conversations/'+c.publicId+'/read',{}).subscribe(()=>this.conversations.update(all=>all.map(x=>x.publicId===c.publicId?{...x,unreadCount:0}:x)));
  }
  protected send(){
    const c=this.activeConversation();if(!c||!this.conversation.body.trim())return;
    this.api.post('/msg/conversations/'+c.publicId+'/messages',{body:this.conversation.body}).subscribe({next:()=>{this.conversation.body='';this.openConversation(c);},error:e=>this.toasts.error(e)});
  }
  protected apply(o:Offering){
    if(!this.trainingJustification.trim())return this.toasts.show('Add a justification first');
    this.api.post('/trn/offerings/'+o.publicId+'/apply',{justification:this.trainingJustification}).subscribe({next:()=>{this.trainingJustification='';this.load('training');this.toasts.show('Application submitted');},error:e=>this.toasts.error(e)});
  }
  protected nextTask(t:TaskRow){return ({OPEN:'ACCEPTED',ACCEPTED:'IN_PROGRESS',IN_PROGRESS:'SUBMITTED',RETURNED:'SUBMITTED',SUBMITTED:'CLOSED'} as Record<string,string>)[t.status];}
  protected startExtension(t:TaskRow){this.extensionTask.set(t);this.extensionDue='';this.extensionReason='';}
  protected requestExtension(){
    const t=this.extensionTask();if(!t||!this.extensionDue||!this.extensionReason.trim())return;
    this.api.post('/tsk/tasks/'+t.publicId+'/extensions',{requestedDueOn:this.extensionDue,justification:this.extensionReason}).subscribe({next:()=>{this.extensionTask.set(null);this.load('tasks');this.toasts.show('Extension request sent');},error:e=>this.toasts.error(e)});
  }
  protected decideExtension(t:TaskRow,decision:'APPROVED'|'REJECTED'){
    this.api.get<{id:number;decision:string}[]>('/tsk/tasks/'+t.publicId+'/extensions').subscribe({next:items=>{const pending=items.find(x=>x.decision==='PENDING');if(!pending)return;this.api.post('/tsk/tasks/'+t.publicId+'/extensions/'+pending.id+'/decision',{decision,comment:''}).subscribe({next:()=>{this.load('tasks');this.toasts.show('Extension '+decision.toLowerCase());},error:e=>this.toasts.error(e)});},error:e=>this.toasts.error(e)});
  }
  protected requestAction(r:RequestRow,status:'RESOLVED'|'CLOSED'){
    const note=status==='RESOLVED'?'Completed by the service desk.':undefined;
    this.api.post('/req/requests/'+r.publicId+'/action',{status,note,satisfaction:status==='CLOSED'?5:null}).subscribe({next:()=>{this.load('requests');this.toasts.show(status==='RESOLVED'?'Request resolved':'Request closed');},error:e=>this.toasts.error(e)});
  }
  private reset(tab:string){
    if(tab==='tasks')this.task={title:'',description:'',priority:'NORMAL',assigneeStaffPublicId:'',dueOn:''};
    if(tab==='messages')this.conversation={title:'',memberPublicId:'',body:''};
    if(tab==='documents')this.document={title:'',typeCode:'POLICY',classification:'INTERNAL',language:'en'};
    if(tab==='projects')this.project={code:'',title:'',type:'INTERNAL',startsOn:''};
    if(tab==='requests')this.request={requestTypeId:this.requestTypes()[0]?.id??0,summary:'',description:''};
    if(tab==='feedback')this.feedback={category:'Service improvement',description:''};
  }
}
