"""Provision the public CatalystFlow development demonstration accounts. Maintainer: Nixon."""
import boto3

session = boto3.Session(profile_name='personal-dev', region_name='ap-southeast-2')
assert session.client('sts').get_caller_identity()['Account'] == '142531840386'
cf = session.client('cloudformation')
outputs = {v['OutputKey']: v['OutputValue'] for v in cf.describe_stacks(StackName='catalystflow-hosting-dev')['Stacks'][0]['Outputs']}
pool = outputs['PoolId']
cognito = session.client('cognito-idp')
table = session.resource('dynamodb').Table('CatalystFlow_Users_dev')
password = 'CatalystDemo@123'  # Intentionally public; only used by synthetic demo accounts.
users = {}
for name, role in [('admin', 'ADMIN'), ('manager', 'MANAGER'), ('employee', 'EMPLOYEE')]:
    email = f'demo.{name}@catalystflow.example.com'
    try:
        cognito.admin_create_user(UserPoolId=pool, Username=email, MessageAction='SUPPRESS', UserAttributes=[{'Name': 'email', 'Value': email}, {'Name': 'email_verified', 'Value': 'true'}, {'Name': 'name', 'Value': f'Demo {name.title()}'}])
    except cognito.exceptions.UsernameExistsException:
        pass
    cognito.admin_set_user_password(UserPoolId=pool, Username=email, Password=password, Permanent=True)
    cognito.admin_add_user_to_group(UserPoolId=pool, Username=email, GroupName=role)
    user = cognito.admin_get_user(UserPoolId=pool, Username=email)
    user_id = next(a['Value'] for a in user['UserAttributes'] if a['Name'] == 'sub')
    users[name] = user_id
    item = {'userId': user_id, 'email': email, 'firstName': 'Demo', 'lastName': name.title(), 'role': role, 'position': f'Demo {name.title()}', 'status': 'Active', 'discipline': 'Engineering', 'seniority': 'Senior'}
    if name == 'manager': item['reportingTo'] = users['admin']
    if name == 'employee': item['reportingTo'] = users['manager']
    table.put_item(Item=item)
    auth = cognito.admin_initiate_auth(UserPoolId=pool, ClientId=outputs['ClientId'], AuthFlow='ADMIN_USER_PASSWORD_AUTH', AuthParameters={'USERNAME': email, 'PASSWORD': password})
    assert auth['AuthenticationResult']['IdToken']
    print(f'{role}: account and sign-in verified')
print('Synthetic demo users provisioned. No email sent.')
