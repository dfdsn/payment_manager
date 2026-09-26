import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { switchMap } from 'rxjs';
export interface Category { id: string; name: string; archived: boolean; version: number; updatedAt: string; }
@Injectable({providedIn:'root'})
export class CategoryService {
  private readonly http=inject(HttpClient); private readonly endpoint='/api/v1/categories';
  list(includeArchived=false){return this.http.get<Category[]>(this.endpoint,{params:{includeArchived}});}
  create(name:string){return this.mutate(()=>this.http.post<Category>(this.endpoint,{name}));}
  rename(category:Category,name:string){return this.mutate(()=>this.http.put<Category>(`${this.endpoint}/${category.id}`,{name,version:category.version}));}
  archive(category:Category){return this.mutate(()=>this.http.post<Category>(`${this.endpoint}/${category.id}/archive`,{version:category.version}));}
  private mutate<T>(action:()=>import('rxjs').Observable<T>){return this.http.get('/api/v1/auth/csrf').pipe(switchMap(action));}
}
