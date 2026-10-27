import { UserRole, EmployeeStatus } from '../../models/employee.model';

export interface DisplayEmployee {
  id: number;
  userId: string;
  firstName: string;
  lastName: string;
  name: string;
  email: string;
  role: string;           // position/job title
  employeeType: string;   // Admin | Manager | Employee
  dbRole: UserRole;
  reportsTo: string;
  reportingToId: string | null;
  status: EmployeeStatus;
  hireDate: string;
  seniority?: string;
  discipline?: string;
  teamMembers?: { userId: string; name: string }[];
}
