class Solution {
    vector<vector<vector<long long>>> dp;
public:
    long long rec(int l,int r,bool isLeft, vector<int> &req){
        if(l <= 0 && r >= size(req)-1) return 0;
        long long res = 1e18;
        int n = size(req);
        if(dp[l][r][isLeft] != -1) return dp[l][r][isLeft];
        long long rem = n-(r-l+1);
        if(isLeft){
            if(l > 0)
                res = min(res,abs(req[l]-req[l-1])*rem + rec(l-1,r,isLeft,req));
            if(r < size(req)-1){
                res = min(res,abs(req[r+1]-req[l])*rem + rec(l,r+1,!isLeft,req)); 
            }
        }else{
            if(l > 0) 
                res = min(res,abs(req[r]-req[l-1])*rem + rec(l-1,r,!isLeft,req));
            if(r < size(req)-1){
                res = min(res,abs(req[r+1]-req[r])*rem + rec(l,r+1,isLeft,req)); 
            }
        }
        return dp[l][r][isLeft] = res;
    }
    long long elevatorRequests(int n, int start, vector<int>& requests) {
        bool  contains = false;
        for(int i : requests){
            if(i == start) {
                contains = true;
                break;
            }
        }
        if(!contains){
            requests.push_back(start);
        }
        n = size(requests);
        dp.assign(n,vector<vector<long long>>(n,vector<long long>(2,-1)));
        sort(begin(requests),end(requests));

        int ind = lower_bound(begin(requests),end(requests),start)-begin(requests);
        return rec(ind,ind,true,requests);
    }
};