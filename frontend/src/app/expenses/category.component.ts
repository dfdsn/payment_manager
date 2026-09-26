import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button'; import { MatCardModule } from '@angular/material/card'; import { MatInputModule } from '@angular/material/input'; import { MatFormFieldModule } from '@angular/material/form-field';
import { finalize } from 'rxjs'; import { Category,CategoryService } from './category.service';
@Component({selector:'app-categories',imports:[ReactiveFormsModule,RouterLink,MatButtonModule,MatCardModule,MatInputModule,MatFormFieldModule],templateUrl:'./category.component.html',styleUrl:'./category.component.scss'})
export class CategoryComponent implements OnInit {
 private readonly api=inject(CategoryService); private readonly fb=inject(FormBuilder);
 readonly categories=signal<Category[]>([]); readonly loading=signal(true); readonly saving=signal(false); readonly message=signal<string|null>(null); readonly error=signal<string|null>(null); readonly editing=signal<Category|null>(null);
 readonly form=this.fb.nonNullable.group({name:['',[Validators.required,Validators.maxLength(60)]]});
 ngOnInit(){this.load();} load(){this.loading.set(true);this.api.list(true).pipe(finalize(()=>this.loading.set(false))).subscribe({next:v=>this.categories.set(v),error:e=>this.fail(e)});}
 save(){if(this.form.invalid||this.saving())return this.form.markAllAsTouched(); const current=this.editing();this.saving.set(true);this.error.set(null);const call=current?this.api.rename(current,this.form.controls.name.value):this.api.create(this.form.controls.name.value);call.pipe(finalize(()=>this.saving.set(false))).subscribe({next:()=>{this.message.set(current?'Categoria renomeada.':'Categoria criada.');this.editing.set(null);this.form.reset();this.load();},error:e=>this.fail(e)});}
 edit(c:Category){this.editing.set(c);this.form.setValue({name:c.name});this.error.set(null);}
 archive(c:Category){this.saving.set(true);this.error.set(null);this.api.archive(c).pipe(finalize(()=>this.saving.set(false))).subscribe({next:()=>{this.message.set('Categoria arquivada. Despesas existentes foram preservadas.');this.load();},error:e=>this.fail(e)});}
 private fail(e:HttpErrorResponse){this.error.set(e.error?.message??'Não foi possível concluir. Tente novamente.');}
}
