import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import {
  ArrowUpRight, Banknote, Bell, Briefcase, Building2, Calendar, Check, ChevronLeft, ChevronRight, CircleAlert, CircleCheck,
  Clock, Contrast, Download, Eye, FileText, FileUp, Fingerprint, Funnel, HandCoins, HelpCircle, History, House, Inbox, Info,
  Landmark, Languages, Layers3, ListChecks, Lock, LogOut, Mail, MapPin, Megaphone, Menu, MessageCircleQuestionMark, MessageSquare,
  Moon, MoreHorizontal, Network, Paperclip, Pencil, Phone, PieChart, Plus, Receipt, RotateCcw, Scale, ScrollText, Search, Send,
  Settings, ShieldCheck, Sidebar, Siren, SlidersHorizontal, Sun, Trash2, TriangleAlert, Type, Undo2, Upload, User, UserRoundCog,
  Users, Wallet, X, XCircle,
} from 'lucide-static';

// Lucide icons (ISC licence). SF Symbols are licensed for Apple platforms only, so the web build uses
// a stroke family with matching weight and optical size.
const ICONS: Record<string, string> = {
  home: House, megaphone: Megaphone, wallet: Wallet, users: Users, bell: Bell, settings: Settings, shield: ShieldCheck,
  landmark: Landmark, building: Building2, search: Search, chevronRight: ChevronRight, chevronLeft: ChevronLeft, x: X, check: Check,
  plus: Plus, fileUp: FileUp, file: FileText, paperclip: Paperclip, alert: CircleAlert, warning: TriangleAlert, info: Info,
  clock: Clock, calendar: Calendar, mail: Mail, phone: Phone, logout: LogOut, languages: Languages, sun: Sun, moon: Moon,
  contrast: Contrast, type: Type, inbox: Inbox, send: Send, undo: Undo2, reopen: RotateCcw, checkCircle: CircleCheck,
  xCircle: XCircle, eye: Eye, pencil: Pencil, trash: Trash2, filter: Funnel, message: MessageSquare,
  question: MessageCircleQuestionMark, pie: PieChart, layers: Layers3, checklist: ListChecks, network: Network, user: User,
  userCog: UserRoundCog, history: History, fingerprint: Fingerprint, download: Download, upload: Upload,
  sliders: SlidersHorizontal, help: HelpCircle, external: ArrowUpRight, coins: HandCoins, receipt: Receipt, banknote: Banknote,
  scale: Scale, more: MoreHorizontal, menu: Menu, sidebar: Sidebar, siren: Siren, lock: Lock, pin: MapPin, briefcase: Briefcase,
  scroll: ScrollText,
};

const cache = new Map<string, SafeHtml>();

/** <app-icon name="wallet" /> — size follows --icon-size (default 1.25em); colour follows currentColor. */
@Component({
  selector: 'app-icon',
  template: '',
  host: { '[innerHTML]': 'svg()', 'aria-hidden': 'true', class: 'app-icon' },
  styles: `
    :host { display: inline-flex; width: var(--icon-size, 1.25em); height: var(--icon-size, 1.25em); flex: none; }
    :host ::ng-deep svg { width: 100%; height: 100%; stroke-width: var(--icon-stroke, 1.9); }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Icon {
  private sanitizer = inject(DomSanitizer);
  readonly name = input.required<string>();
  protected svg = computed(() => {
    const n = this.name();
    let html = cache.get(n);
    if (!html) {
      html = this.sanitizer.bypassSecurityTrustHtml(ICONS[n] ?? ICONS['help']);
      cache.set(n, html);
    }
    return html;
  });
}
