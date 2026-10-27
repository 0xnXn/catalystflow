export type EmployeeStatus = 'Active' | 'Archived';
export type UserRole = 'ADMIN' | 'MANAGER' | 'EMPLOYEE';

export interface User {
  id?: number;
  userId?: string;
  email: string;
  firstName: string;
  lastName: string;
  role: UserRole;
  position: string;
  status: EmployeeStatus;
  reportingTo: string | null;
  hireDate?: string;
  seniority?: string;
  discipline?: string;
  /** ISO-8601 timestamp of the last modification, server-set on every
   *  create/update. Drives the Role Management "Last Modified" column. */
  updatedAt?: string;
}

export interface Team {
  id?: number;
  managerId:    string | undefined;
  managerName:  string;
  managerTitle: string;
  members:      { userId: string; name: string; role: string; email: string }[];
}
