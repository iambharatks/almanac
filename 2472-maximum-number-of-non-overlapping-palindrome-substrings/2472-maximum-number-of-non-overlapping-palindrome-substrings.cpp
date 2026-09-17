class Solution1 {
    //O(N^3)
    vector<vector<int>> dp;
    vector<vector<int>> isPal;
public:
    bool isPalindrome(int l,int r,string &s){
        if(isPal[l][r] != -1) return isPal[l][r];
        while(l < r){
            if(s[l++] != s[r--]) return isPal[l][r] = false;
        }
        return isPal[l][r] = true;
    }
    int rec(int l,int r,string &s, int &k){
        if(dp[l][r] != -1) return dp[l][r];
        if(r-l+1 < k) return dp[l][r] = 0;
        int i = l, j = r;
        int res = isPalindrome(l,r,s);
        for(int i = l ; i < r ; i++){
            res = max(res,rec(l,i,s,k)+rec(i+1,r,s,k));
        }
        return dp[l][r] = res;
    }
    int maxPalindromes(string s, int k) {
        dp.assign(size(s),vector<int>(size(s),-1));
        isPal.assign(size(s),vector<int>(size(s),-1));
        return rec(0,size(s)-1,s,k);
    }
};
class Solution {
    vector<vector<bool>> isPal;
    vector<int> dp;
public:
    bool isPalindrome(int l,int r,string &s){
        while(l < r){
            if(s[l++] != s[r--]) return false;
        }
        return true;
    }
    void precomputePalindromes(string& s, int k){
        int n = size(s);
        for(int i = k-1 ; i < n ; i++ ){
            isPal[i][0] = isPalindrome(i-k+1,i,s);
            if(i > k-1){
                isPal[i][1] = isPalindrome(i-k,i,s);
            }
        }
    }
    int maxPalindromes(string s, int k) {
        int n = size(s);
        isPal.assign(n,vector<bool>(2));
        precomputePalindromes(s, k);
        dp.assign(n,0);
        for(int i = 0 ; i < n ; i++){
            if(i > 0)
                dp[i] = max(dp[i-1],dp[i]);
            
            if(i >= k-1 && isPal[i][0]){
                int prev = (i >= k)?dp[i-k]:0;
                dp[i] = max(dp[i],prev+1);
            }
            if(i >= k && isPal[i][1]){
                int prev = (i > k)?dp[i-k-1]:0;
                dp[i] = max(dp[i],prev+1);
            }
        }
        return dp.back();
    }
};