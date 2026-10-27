import { TestBed } from '@angular/core/testing';
import { AuthService, CognitoTokenPayload } from './auth.service';
import { CognitoUser, CognitoUserSession } from 'amazon-cognito-identity-js';

describe('AuthService', () => {
  let service: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AuthService]
    });

    service = TestBed.inject(AuthService);
    sessionStorage.clear();
  });

  afterEach(() => {
    sessionStorage.clear();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });


//Login tests
//Simulates successful login

  it('should emit success when login succeeds', (done) => {
    spyOn(CognitoUser.prototype, 'authenticateUser').and.callFake(function (
      _authDetails: any,
      callbacks: any
    ) {
      callbacks.onSuccess({} as CognitoUserSession);
    });

    service.login('test@example.com', 'Password123!').subscribe({
      next: (result) => {
        expect(result).toBe('success');
        expect(service.hasPendingChallenge()).toBeFalse();
        done();
      },
      error: done.fail
    });
  });

  // Simulates first-time login requiring password reset

  it('should emit newPasswordRequired when first login requires password reset', (done) => {
    spyOn(CognitoUser.prototype, 'authenticateUser').and.callFake(function (
      _authDetails: any,
      callbacks: any
    ) {
      callbacks.newPasswordRequired({}, {});
    });

    service.login('test@example.com', 'TempPassword123!').subscribe({
      next: (result) => {
        expect(result).toBe('newPasswordRequired');
        expect(service.hasPendingChallenge()).toBeTrue();
        done();
      },
      error: done.fail
    });
  });


// Simulates login failure

  it('should error when login fails', (done) => {
    const mockError = new Error('NotAuthorizedException');

    spyOn(CognitoUser.prototype, 'authenticateUser').and.callFake(function (
      _authDetails: any,
      callbacks: any
    ) {
      callbacks.onFailure(mockError);
    });

    service.login('test@example.com', 'WrongPassword').subscribe({
      next: () => done.fail('Expected error'),
      error: (err) => {
        expect(err).toBe(mockError);
        done();
      }
    });
  });

//New Password
// Ensures error is thrown if no pending challenge user exists

  it('should error if completeNewPassword is called without pending challenge user', (done) => {
    (service as any).pendingChallengeUser = null;

    service.completeNewPassword('NewPassword123!').subscribe({
      next: () => done.fail('Expected error'),
      error: (err) => {
        expect(err.message).toBe('No pending challenge. Please log in again.');
        done();
      }
    });
  });

  // Simulates successful password reset

  it('should complete new password challenge successfully', (done) => {
    const mockPendingUser = jasmine.createSpyObj('CognitoUser', ['completeNewPasswordChallenge']);

    mockPendingUser.completeNewPasswordChallenge.and.callFake(
      (_newPassword: string, _attrs: any, callbacks: any) => {
        callbacks.onSuccess({} as CognitoUserSession);
      }
    );

    (service as any).pendingChallengeUser = mockPendingUser;

    service.completeNewPassword('NewPassword123!').subscribe({
      next: () => {},
      complete: () => {
        expect(mockPendingUser.completeNewPasswordChallenge).toHaveBeenCalled();
        expect(service.hasPendingChallenge()).toBeFalse();
        done();
      },
      error: done.fail
    });
  });

  // Simulates failure during password reset

  it('should error when completeNewPassword challenge fails', (done) => {
    const mockPendingUser = jasmine.createSpyObj('CognitoUser', ['completeNewPasswordChallenge']);
    const mockError = new Error('InvalidPasswordException');

    mockPendingUser.completeNewPasswordChallenge.and.callFake(
      (_newPassword: string, _attrs: any, callbacks: any) => {
        callbacks.onFailure(mockError);
      }
    );

    (service as any).pendingChallengeUser = mockPendingUser;

    service.completeNewPassword('weak-password').subscribe({
      next: () => done.fail('Expected error'),
      error: (err) => {
        expect(err).toBe(mockError);
        done();
      }
    });
  });

//Authentication State
// Simulates valid session
  it('should return true from isAuthenticated when session is valid', (done) => {
    const mockCurrentUser = {
      getSession: (cb: Function) => {
        cb(null, {
          isValid: () => true
        });
      }
    };

    const mockUserPool = jasmine.createSpyObj('CognitoUserPool', ['getCurrentUser']);
    mockUserPool.getCurrentUser.and.returnValue(mockCurrentUser);

    Object.defineProperty(service as any, 'userPool', {
      value: mockUserPool,
      configurable: true
    });

    service.isAuthenticated().subscribe({
      next: (result) => {
        expect(result).toBeTrue();
      },
      complete: done,
      error: done.fail
    });
  });

  // Simulates invalid session
  it('should return false from isAuthenticated when session is invalid', (done) => {
    const mockCurrentUser = {
      getSession: (cb: Function) => {
        cb(null, {
          isValid: () => false
        });
      }
    };

    const mockUserPool = jasmine.createSpyObj('CognitoUserPool', ['getCurrentUser']);
    mockUserPool.getCurrentUser.and.returnValue(mockCurrentUser);

    Object.defineProperty(service as any, 'userPool', {
      value: mockUserPool,
      configurable: true
    });

    service.isAuthenticated().subscribe({
      next: (result) => {
        expect(result).toBeFalse();
      },
      complete: done,
      error: done.fail
    });
  });

// Logout
//Ensures logout triggers signOut
  it('should logout current user and clear pending challenge', () => {
    const mockCurrentUser = jasmine.createSpyObj('CognitoUser', ['signOut']);
    const mockUserPool = jasmine.createSpyObj('CognitoUserPool', ['getCurrentUser']);
    mockUserPool.getCurrentUser.and.returnValue(mockCurrentUser);

    Object.defineProperty(service as any, 'userPool', {
      value: mockUserPool,
      configurable: true
    });

    (service as any).pendingChallengeUser = {} as CognitoUser;

    service.logout();

    expect(mockCurrentUser.signOut).toHaveBeenCalled();
    expect(service.hasPendingChallenge()).toBeFalse();
  });

  //Token Handling
  //Retrieves id token from sessionStorage

  it('should return id token from sessionStorage', () => {
    spyOn(sessionStorage, 'getItem').and.callFake((key: string) => {
      if (key.includes('LastAuthUser')) return 'test@example.com';
      if (key.endsWith('.idToken')) return 'mock.jwt.token';
      return null;
    });

    expect(service.getIdToken()).toBe('mock.jwt.token');
  });

  // Returns null if no user is stored

  it('should return null from getIdToken when no LastAuthUser exists', () => {
    spyOn(sessionStorage, 'getItem').and.returnValue(null);

    expect(service.getIdToken()).toBeNull();
  });

  // Decodes JWT payload

  it('should decode and return token payload', () => {
    const payload: CognitoTokenPayload = {
      sub: '123',
      email: 'admin@example.com',
      'cognito:groups': ['ADMIN']
    };

    const encodedPayload = btoa(JSON.stringify(payload))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');

    spyOn(service, 'getIdToken').and.returnValue(`header.${encodedPayload}.signature`);

    expect(service.getTokenPayload()).toEqual(payload);
  });

  // Handles malformed token safely
  it('should return null when token is malformed', () => {
    spyOn(service, 'getIdToken').and.returnValue('bad-token');

    expect(service.getTokenPayload()).toBeNull();
  });

  it('should return null when getIdToken returns null', () => {
    spyOn(service, 'getIdToken').and.returnValue(null);

    expect(service.getTokenPayload()).toBeNull();
  });

  it('should return true for isAdmin when token payload contains ADMIN group', () => {
    spyOn(service, 'getTokenPayload').and.returnValue({
      sub: '123',
      email: 'admin@example.com',
      'cognito:groups': ['ADMIN']
    });

    expect(service.isAdmin()).toBeTrue();
  });


  it('should return user email from token payload', () => {
    spyOn(service, 'getTokenPayload').and.returnValue({
      sub: '123',
      email: 'user@example.com',
      'cognito:groups': ['USER']
    });

    expect(service.getUserEmail()).toBe('user@example.com');
  });

  it('should return null from getUserEmail when token payload is null', () => {
    spyOn(service, 'getTokenPayload').and.returnValue(null);

    expect(service.getUserEmail()).toBeNull();
  });
  

  it('should return false from isAuthenticated when getSession returns error', (done) => {
  const mockCurrentUser = {
    getSession: (cb: Function) => {
      cb(new Error('Session error'), null);
    }
  };

  const mockUserPool = jasmine.createSpyObj('CognitoUserPool', ['getCurrentUser']);
  mockUserPool.getCurrentUser.and.returnValue(mockCurrentUser);

  Object.defineProperty(service as any, 'userPool', {
    value: mockUserPool,
    configurable: true
  });

  service.isAuthenticated().subscribe({
    next: (result) => {
      expect(result).toBeFalse();
    },
    complete: done,
    error: done.fail
  });
});

it('should return false from isAuthenticated when session is null', (done) => {
  const mockCurrentUser = {
    getSession: (cb: Function) => {
      cb(null, null);
    }
  };

  const mockUserPool = jasmine.createSpyObj('CognitoUserPool', ['getCurrentUser']);
  mockUserPool.getCurrentUser.and.returnValue(mockCurrentUser);

  Object.defineProperty(service as any, 'userPool', {
    value: mockUserPool,
    configurable: true
  });

  service.isAuthenticated().subscribe({
    next: (result) => {
      expect(result).toBeFalse();
    },
    complete: done,
    error: done.fail
  });
});

it('should clear pending challenge even when no current user exists on logout', () => {
  const mockUserPool = jasmine.createSpyObj('CognitoUserPool', ['getCurrentUser']);
  mockUserPool.getCurrentUser.and.returnValue(null);

  Object.defineProperty(service as any, 'userPool', {
    value: mockUserPool,
    configurable: true
  });

  (service as any).pendingChallengeUser = {} as CognitoUser;

  expect(() => service.logout()).not.toThrow();
  expect(service.hasPendingChallenge()).toBeFalse();
});

it('should return null from getIdToken when LastAuthUser exists but idToken is missing', () => {
  spyOn(sessionStorage, 'getItem').and.callFake((key: string) => {
    if (key.includes('LastAuthUser')) return 'test@example.com';
    if (key.endsWith('.idToken')) return null;
    return null;
  });

  expect(service.getIdToken()).toBeNull();
});

it('should return null when token payload is not valid JSON', () => {
  const invalidJson = btoa('not-json')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

  spyOn(service, 'getIdToken').and.returnValue(`header.${invalidJson}.signature`);

  expect(service.getTokenPayload()).toBeNull();
});

it('should return false for isAdmin when token payload has no cognito groups', () => {
  spyOn(service, 'getTokenPayload').and.returnValue({
    sub: '123',
    email: 'user@example.com'
  } as any);

  expect(service.isAdmin()).toBeFalse();
});

it('should return false for isAdmin when cognito groups is empty', () => {
  spyOn(service, 'getTokenPayload').and.returnValue({
    sub: '123',
    email: 'user@example.com',
    'cognito:groups': []
  });

  expect(service.isAdmin()).toBeFalse();
});

it('should return null from getUserEmail when email is missing in token payload', () => {
  spyOn(service, 'getTokenPayload').and.returnValue({
    sub: '123',
    'cognito:groups': ['USER']
  } as any);

  expect(service.getUserEmail()).toBeNull();
});

// changePassword
// Ensures error is thrown if no authenticated user exists


it('should error when changePassword is called without authenticated user', (done) => {
  const mockUserPool = jasmine.createSpyObj('CognitoUserPool', ['getCurrentUser']);
  mockUserPool.getCurrentUser.and.returnValue(null);

  Object.defineProperty(service as any, 'userPool', {
    value: mockUserPool,
    configurable: true
  });

  service.changePassword('OldPassword123!', 'NewPassword123!').subscribe({
    next: () => done.fail('Expected error'),
    error: (err) => {
      expect(err.message).toBe('No authenticated user found. Please log in again.');
      done();
    }
  });
});

it('should error when getSession returns an error during changePassword', (done) => {
  const sessionError = new Error('Session retrieval failed');

  const mockCurrentUser = {
    getSession: (cb: Function) => cb(sessionError, null)
  };

  const mockUserPool = jasmine.createSpyObj('CognitoUserPool', ['getCurrentUser']);
  mockUserPool.getCurrentUser.and.returnValue(mockCurrentUser);

  Object.defineProperty(service as any, 'userPool', {
    value: mockUserPool,
    configurable: true
  });

  service.changePassword('OldPassword123!', 'NewPassword123!').subscribe({
    next: () => done.fail('Expected error'),
    error: (err) => {
      expect(err).toBe(sessionError);
      done();
    }
  });
});

it('should error when session is invalid during changePassword', (done) => {
  const mockCurrentUser = {
    getSession: (cb: Function) =>
      cb(null, {
        isValid: () => false
      })
  };

  const mockUserPool = jasmine.createSpyObj('CognitoUserPool', ['getCurrentUser']);
  mockUserPool.getCurrentUser.and.returnValue(mockCurrentUser);

  Object.defineProperty(service as any, 'userPool', {
    value: mockUserPool,
    configurable: true
  });

  service.changePassword('OldPassword123!', 'NewPassword123!').subscribe({
    next: () => done.fail('Expected error'),
    error: (err) => {
      expect(err.message).toBe('Session expired. Please log in again.');
      done();
    }
  });
});

it('should error when Cognito changePassword fails', (done) => {
  const changeError = new Error('InvalidPasswordException');

  const mockCurrentUser = {
    getSession: (cb: Function) =>
      cb(null, {
        isValid: () => true
      }),
    changePassword: (_current: string, _new: string, cb: Function) =>
      cb(changeError, null)
  };

  const mockUserPool = jasmine.createSpyObj('CognitoUserPool', ['getCurrentUser']);
  mockUserPool.getCurrentUser.and.returnValue(mockCurrentUser);

  Object.defineProperty(service as any, 'userPool', {
    value: mockUserPool,
    configurable: true
  });

  service.changePassword('OldPassword123!', 'weak-password').subscribe({
    next: () => done.fail('Expected error'),
    error: (err) => {
      expect(err).toBe(changeError);
      done();
    }
  });
});

// Successful password change
it('should complete successfully when changePassword succeeds', (done) => {
  const mockCurrentUser = {
    getSession: (cb: Function) =>
      cb(null, {
        isValid: () => true
      }),
    changePassword: (_current: string, _new: string, cb: Function) =>
      cb(null, 'SUCCESS')
  };

  const mockUserPool = jasmine.createSpyObj('CognitoUserPool', ['getCurrentUser']);
  mockUserPool.getCurrentUser.and.returnValue(mockCurrentUser);

  Object.defineProperty(service as any, 'userPool', {
    value: mockUserPool,
    configurable: true
  });

  service.changePassword('OldPassword123!', 'NewPassword123!').subscribe({
    next: (value) => {
      expect(value).toBeUndefined();
    },
    complete: done,
    error: done.fail
  });
});


});