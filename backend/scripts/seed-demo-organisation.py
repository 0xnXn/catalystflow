"""Seed a realistic synthetic development organisation. Maintainer: Nixon."""
import boto3
from datetime import date

s = boto3.Session(profile_name='personal-dev', region_name='ap-southeast-2')
assert s.client('sts').get_caller_identity()['Account'] == '142531840386'
o = {r['OutputKey']: r['OutputValue'] for r in s.client('cloudformation').describe_stacks(StackName='catalystflow-hosting-dev')['Stacks'][0]['Outputs']}
c = s.client('cognito-idp'); table = s.resource('dynamodb').Table('CatalystFlow_Users_dev')
software = 'Software & DevOps Engineering'; ba = 'Business Analysis'; qa = 'Quality Assurance'
# Name, role, discipline, seniority, job title, reporting manager, employment type.
people = [
 ('Ava Morgan','ADMIN',software,'Principal','Head of Engineering',None,'Full-time'),
 ('Liam Chen','MANAGER',software,'Principal','Engineering Manager','Ava Morgan','Full-time'),
 ('Priya Nair','MANAGER',ba,'Principal','Business Analysis Manager','Ava Morgan','Full-time'),
 ('Grace Taylor','MANAGER',qa,'Principal','Quality Engineering Manager','Ava Morgan','Full-time'),
 ('Noah Singh','EMPLOYEE',software,'Graduate','Graduate Software Engineer','Liam Chen','Full-time'),
 ('Sofia Bennett','EMPLOYEE',software,'Analyst','Junior Software Engineer','Liam Chen','Full-time'),
 ('Ethan Brooks','EMPLOYEE',software,'Engineer','Software Engineer','Liam Chen','Full-time'),
 ('Maya Patel','EMPLOYEE',software,'Senior Engineer','Senior Backend Engineer','Liam Chen','Full-time'),
 ('Oliver Reed','EMPLOYEE',software,'Associate Principal','Staff Platform Engineer','Liam Chen','Contractor'),
 ('Amelia Clarke','EMPLOYEE',software,'Principal','Principal Engineer','Liam Chen','Full-time'),
 ('Lucas Martin','EMPLOYEE',ba,'Graduate','Graduate Business Analyst','Priya Nair','Full-time'),
 ('Isla Walker','EMPLOYEE',ba,'Analyst','Business Analyst','Priya Nair','Full-time'),
 ('Jack Wilson','EMPLOYEE',ba,'Engineer','Senior Business Analyst','Priya Nair','Contractor'),
 ('Zoe Ahmed','EMPLOYEE',ba,'Senior Engineer','Lead Business Analyst','Priya Nair','Part-time'),
 ('Harry Evans','EMPLOYEE',qa,'Engineer','Test Engineer','Grace Taylor','Full-time'),
 ('Chloe Nguyen','EMPLOYEE',qa,'Senior Engineer','Senior Quality Engineer','Grace Taylor','Full-time'),
 ('Mia Harris','EMPLOYEE',qa,'Associate Principal','Quality Architect','Grace Taylor','Contractor'),
 ('Theo Lewis','EMPLOYEE',software,'Engineer','Frontend Engineer','Demo Manager','Full-time'),
 ('Aria Thomas','EMPLOYEE',software,'Senior Engineer','Senior DevOps Engineer','Demo Manager','Full-time'),
 ('Leo Robinson','EMPLOYEE',software,'Graduate','Graduate Developer','Demo Manager','Full-time'),
 ('Ella Scott','EMPLOYEE',qa,'Engineer','Automation Engineer','Demo Manager','Part-time'),
 ('Oscar Wright','EMPLOYEE',ba,'Analyst','Product Analyst','Demo Manager','Full-time'),
]
def sub(email):
 return next(a['Value'] for a in c.admin_get_user(UserPoolId=o['PoolId'],Username=email)['UserAttributes'] if a['Name']=='sub')
ids={'Demo Manager':sub('demo.manager@catalystflow.example.com')}
for i,(name,role,discipline,seniority,position,parent,employment) in enumerate(people):
 first,last=name.split(' ',1);email=f'{first.lower()}.{last.lower()}@catalystflow.example.com'
 try:c.admin_create_user(UserPoolId=o['PoolId'],Username=email,MessageAction='SUPPRESS',UserAttributes=[{'Name':'email','Value':email},{'Name':'email_verified','Value':'true'},{'Name':'name','Value':name}])
 except c.exceptions.UsernameExistsException:pass
 user_id=sub(email)
 existing=table.get_item(Key={'userId':user_id}).get('Item')
 assert not existing or existing.get('seedDataset')=='organisation-v1','Refusing to overwrite an unowned profile'
 c.admin_set_user_password(UserPoolId=o['PoolId'],Username=email,Password='CatalystDemo@123',Permanent=True)
 c.admin_add_user_to_group(UserPoolId=o['PoolId'],Username=email,GroupName=role)
 item={'userId':user_id,'email':email,'firstName':first,'lastName':last,'role':role,'position':position,'status':'Archived' if name=='Oscar Wright' else 'Active','employeeType':employment,'discipline':discipline,'seniority':seniority,'hireDate':date(2020+i%6,1+i%12,1+i%25).isoformat(),'seedDataset':'organisation-v1'}
 if parent:item['reportingTo']=ids[parent]
 table.put_item(Item=item,ConditionExpression='attribute_not_exists(userId) OR seedDataset = :dataset',ExpressionAttributeValues={':dataset':'organisation-v1'})
 ids[name]=user_id
 print(name,role,seniority)
rows=table.scan()['Items'];seeded=[r for r in rows if r.get('seedDataset')=='organisation-v1']
assert len(seeded)==len(people)
assert all(not r.get('reportingTo') or any(p['userId']==r['reportingTo'] for p in rows) for r in seeded)
print('Verified',len(seeded),'synthetic profiles and reporting lines; no invitations sent.')
